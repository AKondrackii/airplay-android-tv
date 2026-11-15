/*
 * AirPlay TV — JNI bridge between the UxPlay protocol library and Kotlin.
 *
 * Threading: UxPlay calls the raop callbacks from its own pthreads (httpd, RTP,
 * mirror, NTP). Each such thread is attached to the JVM on first use and detached
 * automatically when it exits (pthread key destructor). Kotlin callbacks must
 * therefore be thread-safe and must not block for long, except
 * onClientRequest(), which may block for a bounded time to let the user confirm.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>

#include "raop.h"
#include "dnssd.h"
#include "httpd.h"
#include "logger.h"
#include "global.h"
#include "stream.h"
#include "dnssd_android.h"
#include "alac_bridge.h"

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define ALAC_MAX_SAMPLES (4096 * 2)
#define MAX_METADATA_BYTES (64 * 1024)
#define MAX_COVERART_BYTES (4 * 1024 * 1024)

static JavaVM *g_vm = NULL;
static pthread_key_t g_env_key;
static pthread_once_t g_key_once = PTHREAD_ONCE_INIT;

typedef struct {
    jmethodID onLog, acceptConnection, onClientRequest, onDisplayPin, onRegisterClient, checkRegister,
        onConnectionOpened, onConnectionClosed, onConnectionReset, onVideoCodec, onVideoData,
        onVideoFlush, onVideoPause, onVideoResume, onVideoReset, onMirrorRunning, onAudioFormat,
        onAudioPcm, onAudioCompressed, onAudioFlush, onAudioVolume, onMetadata, onCoverArt,
        onProgress, onVideoPlay, onVideoScrub, onVideoRate, onVideoStop, getPlaybackInfo,
        onPlaylistRemove;
} methods_t;

typedef struct bridge_s {
    raop_t *raop;
    dnssd_t *dnssd;
    jobject callbacks;      /* global ref */
    methods_t m;
    alac_bridge_t *alac;
    int16_t pcm[ALAC_MAX_SAMPLES];
    unsigned char audio_ct;
    int pin_pw;
    char *password;
    int password_len;
    bool running;
    volatile float volume_db;   /* last volume set by the sender, reported back in /info */
    pthread_mutex_t lock;
} bridge_t;

/* The httpd accept hook has no context argument; only one receiver exists per process. */
static bridge_t *volatile g_bridge = NULL;

/* ------------------------------------------------------------------------- */
/* JNI environment helpers                                                   */

static void detach_thread(void *unused) {
    (void) unused;
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

static void make_key(void) {
    pthread_key_create(&g_env_key, detach_thread);
}

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if (!g_vm) return NULL;
    jint st = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (st == JNI_OK) return env;
    if (st == JNI_EDETACHED) {
        JavaVMAttachArgs args = { JNI_VERSION_1_6, "airplay-native", NULL };
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, &args) != JNI_OK) return NULL;
        pthread_once(&g_key_once, make_key);
        pthread_setspecific(g_env_key, (void *) 1);   /* triggers detach on thread exit */
        return env;
    }
    return NULL;
}

static bool check_exception(JNIEnv *env, const char *where) {
    if ((*env)->ExceptionCheck(env)) {
        LOGE("Java exception in callback %s", where);
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return true;
    }
    return false;
}

static jstring new_string_or_null(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    /* Strings come from the network. NewStringUTF needs valid *modified* UTF-8, so keep
       only well-formed 1–3 byte sequences and replace control bytes, 4-byte sequences
       (emoji) and malformed input with '?'. Length is capped at 512 bytes. */
    const unsigned char *u = (const unsigned char *) s;
    size_t n = strnlen(s, 512);
    char buf[513];
    size_t i = 0, j = 0;
    while (i < n && j < sizeof(buf) - 4) {
        unsigned char c = u[i];
        if (c < 0x80) {
            buf[j++] = (c < 0x20 || c == 0x7f) ? '?' : (char) c;
            i++;
        } else if ((c & 0xE0) == 0xC0 && c >= 0xC2 && i + 1 < n && (u[i + 1] & 0xC0) == 0x80) {
            buf[j++] = (char) u[i]; buf[j++] = (char) u[i + 1];
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < n && (u[i + 1] & 0xC0) == 0x80 && (u[i + 2] & 0xC0) == 0x80
                   && !(c == 0xE0 && u[i + 1] < 0xA0)              /* overlong */
                   && !(c == 0xED && u[i + 1] >= 0xA0)) {          /* surrogates */
            buf[j++] = (char) u[i]; buf[j++] = (char) u[i + 1]; buf[j++] = (char) u[i + 2];
            i += 3;
        } else {
            buf[j++] = '?';
            i++;
            while (i < n && (u[i] & 0xC0) == 0x80) i++;   /* skip continuation bytes */
        }
    }
    buf[j] = '\0';
    jstring r = (*env)->NewStringUTF(env, buf);
    if (check_exception(env, "NewStringUTF")) return NULL;
    return r;
}

static jbyteArray new_bytes(JNIEnv *env, const void *data, int len) {
    if (!data || len <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { check_exception(env, "NewByteArray"); return NULL; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    return arr;
}

#define CB_ENV(b) JNIEnv *env = get_env(); if (!env || !(b) || !(b)->callbacks) return

/* ------------------------------------------------------------------------- */
/* Network policy hook (called from the httpd thread after accept())          */

static int accept_filter(const unsigned char *local, int local_len,
                         const unsigned char *remote, int remote_len) {
    bridge_t *b = g_bridge;
    JNIEnv *env = get_env();
    if (!b || !env || !b->callbacks) return 0;      /* fail closed */
    jbyteArray l = new_bytes(env, local, local_len);
    jbyteArray r = new_bytes(env, remote, remote_len);
    jboolean ok = JNI_FALSE;
    if (l && r) {
        ok = (*env)->CallBooleanMethod(env, b->callbacks, b->m.acceptConnection, l, r);
        if (check_exception(env, "acceptConnection")) ok = JNI_FALSE;
    }
    if (l) (*env)->DeleteLocalRef(env, l);
    if (r) (*env)->DeleteLocalRef(env, r);
    return ok ? 1 : 0;
}

/* ------------------------------------------------------------------------- */
/* raop callbacks                                                            */

static void log_cb(void *cls, int level, const char *msg) {
    bridge_t *b = cls;
    CB_ENV(b);
    jstring s = new_string_or_null(env, msg);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onLog, level, s);
    check_exception(env, "onLog");
    if (s) (*env)->DeleteLocalRef(env, s);
}

static void conn_init_cb(void *cls) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onConnectionOpened);
    check_exception(env, "onConnectionOpened");
}

static void conn_destroy_cb(void *cls) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onConnectionClosed);
    check_exception(env, "onConnectionClosed");
}

static void conn_reset_cb(void *cls, int reason) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onConnectionReset, reason);
    check_exception(env, "onConnectionReset");
}

static void conn_feedback_cb(void *cls) { (void) cls; }

static void report_client_request_cb(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    bridge_t *b = cls;
    *admit = false;                                  /* fail closed */
    JNIEnv *env = get_env();
    if (!env || !b || !b->callbacks) return;
    jstring d = new_string_or_null(env, deviceid);
    jstring m = new_string_or_null(env, model);
    jstring n = new_string_or_null(env, name);
    jboolean ok = (*env)->CallBooleanMethod(env, b->callbacks, b->m.onClientRequest, d, m, n);
    if (check_exception(env, "onClientRequest")) ok = JNI_FALSE;
    *admit = ok ? true : false;
    if (d) (*env)->DeleteLocalRef(env, d);
    if (m) (*env)->DeleteLocalRef(env, m);
    if (n) (*env)->DeleteLocalRef(env, n);
}

static void display_pin_cb(void *cls, char *pin) {
    bridge_t *b = cls; CB_ENV(b);
    char four[5] = {0};
    if (pin) memcpy(four, pin, strnlen(pin, 4));     /* random_pw carries "pin\0deviceid" */
    jstring s = new_string_or_null(env, four);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onDisplayPin, s);
    check_exception(env, "onDisplayPin");
    if (s) (*env)->DeleteLocalRef(env, s);
}

static void register_client_cb(void *cls, const char *device_id, const char *pk, const char *name) {
    bridge_t *b = cls; CB_ENV(b);
    jstring d = new_string_or_null(env, device_id);
    jstring p = new_string_or_null(env, pk);
    jstring n = new_string_or_null(env, name);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onRegisterClient, d, p, n);
    check_exception(env, "onRegisterClient");
    if (d) (*env)->DeleteLocalRef(env, d);
    if (p) (*env)->DeleteLocalRef(env, p);
    if (n) (*env)->DeleteLocalRef(env, n);
}

static bool check_register_cb(void *cls, const char *pk) {
    bridge_t *b = cls;
    JNIEnv *env = get_env();
    if (!env || !b || !b->callbacks) return false;
    jstring p = new_string_or_null(env, pk);
    jboolean ok = (*env)->CallBooleanMethod(env, b->callbacks, b->m.checkRegister, p);
    if (check_exception(env, "checkRegister")) ok = JNI_FALSE;
    if (p) (*env)->DeleteLocalRef(env, p);
    return ok ? true : false;
}

static const char *passwd_cb(void *cls, int *len) {
    bridge_t *b = cls;
    if (b->pin_pw == 2 && b->password) {
        *len = b->password_len;
        return b->password;
    } else if (b->pin_pw == 3) {
        *len = -1;            /* random one-time code shown on screen */
    } else {
        *len = 0;
    }
    return NULL;
}

static int video_set_codec_and_size_cb(void *cls, video_codec_t codec, unsigned short ws, unsigned short hs,
                                       unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    bridge_t *b = cls;
    JNIEnv *env = get_env();
    if (!env || !b || !b->callbacks) return -1;
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoCodec, codec == VIDEO_CODEC_H265 ? JNI_TRUE : JNI_FALSE,
                           (jint) ws, (jint) hs, (jint) w, (jint) h);
    check_exception(env, "onVideoCodec");
    return 0;
}

static void video_process_cb(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    bridge_t *b = cls; CB_ENV(b);
    if (!data || !data->data || data->data_len <= 0) return;
    jobject buf = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
    if (!buf) { check_exception(env, "NewDirectByteBuffer"); return; }
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoData, buf, (jint) data->data_len,
                           (jlong) data->ntp_time_remote, data->is_h265 ? JNI_TRUE : JNI_FALSE);
    check_exception(env, "onVideoData");
    (*env)->DeleteLocalRef(env, buf);
}

static void simple_cb(bridge_t *b, jmethodID mid, const char *name) {
    CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, mid);
    check_exception(env, name);
}

static void video_flush_cb(void *cls)  { bridge_t *b = cls; simple_cb(b, b->m.onVideoFlush, "onVideoFlush"); }
static void video_pause_cb(void *cls)  { bridge_t *b = cls; simple_cb(b, b->m.onVideoPause, "onVideoPause"); }
static void video_resume_cb(void *cls) { bridge_t *b = cls; simple_cb(b, b->m.onVideoResume, "onVideoResume"); }
static void audio_flush_cb(void *cls)  { bridge_t *b = cls; simple_cb(b, b->m.onAudioFlush, "onAudioFlush"); }
static void video_stop_cb(void *cls)   { bridge_t *b = cls; simple_cb(b, b->m.onVideoStop, "onVideoStop"); }

static void video_reset_cb(void *cls, reset_type_t type) {
    bridge_t *b = cls;
    if (type == RESET_TYPE_NOHOLD && b->raop) {
        /* a new client took over: drop any HLS session of the previous one (as UxPlay does) */
        raop_destroy_airplay_video(b->raop, -1);
    }
    CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoReset, (jint) type);
    check_exception(env, "onVideoReset");
}

static void mirror_video_running_cb(void *cls, bool running) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onMirrorRunning, running ? JNI_TRUE : JNI_FALSE);
    check_exception(env, "onMirrorRunning");
}

static void audio_get_format_cb(void *cls, unsigned char *ct, unsigned short *spf, bool *using_screen,
                                bool *is_media, uint64_t *audio_format) {
    bridge_t *b = cls;
    b->audio_ct = *ct;
    if (*ct == 2 && !b->alac) b->alac = alac_bridge_create();
    CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onAudioFormat, (jint) *ct, (jint) *spf,
                           *using_screen ? JNI_TRUE : JNI_FALSE, *is_media ? JNI_TRUE : JNI_FALSE);
    check_exception(env, "onAudioFormat");
}

static void audio_process_cb(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    bridge_t *b = cls; CB_ENV(b);
    if (!data || !data->data || data->data_len <= 0) return;
    if (data->ct == 2) {
        if (!b->alac) return;
        int samples = alac_bridge_decode(b->alac, data->data, data->data_len, b->pcm, ALAC_MAX_SAMPLES);
        if (samples <= 0) return;
        jobject buf = (*env)->NewDirectByteBuffer(env, b->pcm, (jlong) samples * 2);
        if (!buf) { check_exception(env, "NewDirectByteBuffer"); return; }
        (*env)->CallVoidMethod(env, b->callbacks, b->m.onAudioPcm, buf, (jint) (samples * 2));
        check_exception(env, "onAudioPcm");
        (*env)->DeleteLocalRef(env, buf);
    } else if (data->ct == 4 || data->ct == 8) {
        jobject buf = (*env)->NewDirectByteBuffer(env, data->data, data->data_len);
        if (!buf) { check_exception(env, "NewDirectByteBuffer"); return; }
        (*env)->CallVoidMethod(env, b->callbacks, b->m.onAudioCompressed, buf, (jint) data->data_len,
                               (jint) data->ct);
        check_exception(env, "onAudioCompressed");
        (*env)->DeleteLocalRef(env, buf);
    }
}

static double audio_set_client_volume_cb(void *cls) {
    bridge_t *b = cls;
    return b ? (double) b->volume_db : -15.0;
}

/* UxPlay calls these without NULL checks; the app has no use for them. */
static void export_dacp_cb(void *cls, const char *active_remote, const char *dacp_id) { (void) cls; }
static void audio_remote_control_id_cb(void *cls, const char *dacp_id, const char *active_remote) { (void) cls; }
static void audio_stop_coverart_rendering_cb(void *cls) { (void) cls; }
static void get_custom_profile_cb(void *cls, const char **custom_profile) { *custom_profile = NULL; }

static void audio_set_volume_cb(void *cls, float volume) {
    bridge_t *b = cls;
    if (b) b->volume_db = volume;
    CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onAudioVolume, (jfloat) volume);
    check_exception(env, "onAudioVolume");
}

static void audio_set_metadata_cb(void *cls, const void *buffer, int buflen) {
    bridge_t *b = cls; CB_ENV(b);
    if (buflen <= 0 || buflen > MAX_METADATA_BYTES) return;
    jbyteArray arr = new_bytes(env, buffer, buflen);
    if (!arr) return;
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onMetadata, arr);
    check_exception(env, "onMetadata");
    (*env)->DeleteLocalRef(env, arr);
}

static void audio_set_coverart_cb(void *cls, const void *buffer, int buflen) {
    bridge_t *b = cls; CB_ENV(b);
    if (buflen <= 0 || buflen > MAX_COVERART_BYTES) return;
    jbyteArray arr = new_bytes(env, buffer, buflen);
    if (!arr) return;
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onCoverArt, arr);
    check_exception(env, "onCoverArt");
    (*env)->DeleteLocalRef(env, arr);
}

static void audio_set_progress_cb(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onProgress,
                           (jlong) *start, (jlong) *curr, (jlong) *end);
    check_exception(env, "onProgress");
}

static void on_video_play_cb(void *cls, const char *location, const float start) {
    bridge_t *b = cls; CB_ENV(b);
    jstring s = new_string_or_null(env, location);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoPlay, s, (jfloat) start);
    check_exception(env, "onVideoPlay");
    if (s) (*env)->DeleteLocalRef(env, s);
}

static void on_video_scrub_cb(void *cls, const float position) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoScrub, (jfloat) position);
    check_exception(env, "onVideoScrub");
}

static void on_video_rate_cb(void *cls, const float rate) {
    bridge_t *b = cls; CB_ENV(b);
    (*env)->CallVoidMethod(env, b->callbacks, b->m.onVideoRate, (jfloat) rate);
    check_exception(env, "onVideoRate");
}

static void on_video_acquire_playback_info_cb(void *cls, playback_info_t *info) {
    bridge_t *b = cls;
    info->duration = -1.0;
    info->position = -1.0;
    JNIEnv *env = get_env();
    if (!env || !b || !b->callbacks) return;
    jdoubleArray arr = (*env)->CallObjectMethod(env, b->callbacks, b->m.getPlaybackInfo);
    if (check_exception(env, "getPlaybackInfo") || !arr) return;
    if ((*env)->GetArrayLength(env, arr) >= 7) {
        jdouble v[7];
        (*env)->GetDoubleArrayRegion(env, arr, 0, 7, v);
        info->duration = v[0];
        info->position = v[1];
        info->rate = (float) v[2];
        info->ready_to_play = v[3] != 0.0;
        info->playback_buffer_empty = v[4] != 0.0;
        info->playback_buffer_full = !info->playback_buffer_empty;
        info->playback_likely_to_keep_up = !info->playback_buffer_empty;
        info->seek_start = v[5];
        info->seek_duration = v[6];
    }
    (*env)->DeleteLocalRef(env, arr);
}

static float on_video_playlist_remove_cb(void *cls) {
    bridge_t *b = cls;
    JNIEnv *env = get_env();
    if (!env || !b || !b->callbacks) return -1.0f;
    jfloat pos = (*env)->CallFloatMethod(env, b->callbacks, b->m.onPlaylistRemove);
    if (check_exception(env, "onPlaylistRemove")) return -1.0f;
    return pos;
}

/* ------------------------------------------------------------------------- */
/* JNI entry points                                                          */

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static bool lookup_methods(JNIEnv *env, jclass c, methods_t *m) {
#define M(field, name, sig) m->field = (*env)->GetMethodID(env, c, name, sig); if (!m->field) return false;
    M(onLog, "onLog", "(ILjava/lang/String;)V")
    M(acceptConnection, "acceptConnection", "([B[B)Z")
    M(onClientRequest, "onClientRequest", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z")
    M(onDisplayPin, "onDisplayPin", "(Ljava/lang/String;)V")
    M(onRegisterClient, "onRegisterClient", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
    M(checkRegister, "checkRegister", "(Ljava/lang/String;)Z")
    M(onConnectionOpened, "onConnectionOpened", "()V")
    M(onConnectionClosed, "onConnectionClosed", "()V")
    M(onConnectionReset, "onConnectionReset", "(I)V")
    M(onVideoCodec, "onVideoCodec", "(ZIIII)V")
    M(onVideoData, "onVideoData", "(Ljava/nio/ByteBuffer;IJZ)V")
    M(onVideoFlush, "onVideoFlush", "()V")
    M(onVideoPause, "onVideoPause", "()V")
    M(onVideoResume, "onVideoResume", "()V")
    M(onVideoReset, "onVideoReset", "(I)V")
    M(onMirrorRunning, "onMirrorRunning", "(Z)V")
    M(onAudioFormat, "onAudioFormat", "(IIZZ)V")
    M(onAudioPcm, "onAudioPcm", "(Ljava/nio/ByteBuffer;I)V")
    M(onAudioCompressed, "onAudioCompressed", "(Ljava/nio/ByteBuffer;II)V")
    M(onAudioFlush, "onAudioFlush", "()V")
    M(onAudioVolume, "onAudioVolume", "(F)V")
    M(onMetadata, "onMetadata", "([B)V")
    M(onCoverArt, "onCoverArt", "([B)V")
    M(onProgress, "onProgress", "(JJJ)V")
    M(onVideoPlay, "onVideoPlay", "(Ljava/lang/String;F)V")
    M(onVideoScrub, "onVideoScrub", "(F)V")
    M(onVideoRate, "onVideoRate", "(F)V")
    M(onVideoStop, "onVideoStop", "()V")
    M(getPlaybackInfo, "getPlaybackInfo", "()[D")
    M(onPlaylistRemove, "onPlaylistRemove", "()F")
#undef M
    return true;
}

static void bridge_free(JNIEnv *env, bridge_t *b) {
    if (!b) return;
    if (b->raop) { raop_destroy(b->raop); b->raop = NULL; }
    if (b->dnssd) { dnssd_destroy(b->dnssd); b->dnssd = NULL; }
    if (b->alac) { alac_bridge_destroy(b->alac); b->alac = NULL; }
    if (b->password) {
        memset(b->password, 0, (size_t) b->password_len);
        free(b->password);
        b->password = NULL;
    }
    if (b->callbacks) { (*env)->DeleteGlobalRef(env, b->callbacks); b->callbacks = NULL; }
    pthread_mutex_destroy(&b->lock);
    free(b);
}

JNIEXPORT jlong JNICALL
Java_com_airplaytv_airplay_NativeBridge_nativeCreate(JNIEnv *env, jclass clazz, jobject callbacks,
        jstring jname, jbyteArray jhw, jstring jkeyfile, jint pin_mode, jint pin, jbyteArray jpassword,
        jint width, jint height, jint fps, jboolean hls, jboolean h265, jint log_level) {
    if (g_bridge) {
        LOGE("receiver already created");
        return 0;
    }
    bridge_t *b = calloc(1, sizeof(bridge_t));
    if (!b) return 0;
    pthread_mutex_init(&b->lock, NULL);
    b->pin_pw = pin_mode;
    b->volume_db = -15.0f;

    jclass cbc = (*env)->GetObjectClass(env, callbacks);
    if (!lookup_methods(env, cbc, &b->m)) {
        check_exception(env, "lookup_methods");
        LOGE("callback method lookup failed");
        pthread_mutex_destroy(&b->lock);
        free(b);
        return 0;
    }
    b->callbacks = (*env)->NewGlobalRef(env, callbacks);

    if (jpassword && pin_mode == 2) {
        jsize n = (*env)->GetArrayLength(env, jpassword);
        if (n > 0 && n <= 64) {
            b->password = calloc(1, (size_t) n + 1);
            if (b->password) {
                (*env)->GetByteArrayRegion(env, jpassword, 0, n, (jbyte *) b->password);
                b->password_len = n;
            }
        }
        if (!b->password) { bridge_free(env, b); return 0; }
    }

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.cls = b;
    cbs.conn_init = conn_init_cb;
    cbs.conn_destroy = conn_destroy_cb;
    cbs.conn_reset = conn_reset_cb;
    cbs.conn_feedback = conn_feedback_cb;
    cbs.audio_process = audio_process_cb;
    cbs.video_process = video_process_cb;
    cbs.audio_flush = audio_flush_cb;
    cbs.video_flush = video_flush_cb;
    cbs.video_pause = video_pause_cb;
    cbs.video_resume = video_resume_cb;
    cbs.audio_set_volume = audio_set_volume_cb;
    cbs.audio_get_format = audio_get_format_cb;
    cbs.audio_set_metadata = audio_set_metadata_cb;
    cbs.audio_set_coverart = audio_set_coverart_cb;
    cbs.audio_set_progress = audio_set_progress_cb;
    cbs.report_client_request = report_client_request_cb;
    cbs.display_pin = display_pin_cb;
    cbs.register_client = register_client_cb;
    cbs.check_register = check_register_cb;
    cbs.passwd = passwd_cb;
    cbs.video_reset = video_reset_cb;
    cbs.video_set_codec_and_size = video_set_codec_and_size_cb;
    cbs.mirror_video_running = mirror_video_running_cb;
    cbs.on_video_play = on_video_play_cb;
    cbs.on_video_scrub = on_video_scrub_cb;
    cbs.on_video_rate = on_video_rate_cb;
    cbs.on_video_stop = video_stop_cb;
    cbs.on_video_acquire_playback_info = on_video_acquire_playback_info_cb;
    cbs.on_video_playlist_remove = on_video_playlist_remove_cb;
    cbs.audio_set_client_volume = audio_set_client_volume_cb;
    cbs.export_dacp = export_dacp_cb;
    cbs.audio_remote_control_id = audio_remote_control_id_cb;
    cbs.audio_stop_coverart_rendering = audio_stop_coverart_rendering_cb;
    cbs.get_custom_profile = get_custom_profile_cb;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    jsize hw_len = (*env)->GetArrayLength(env, jhw);
    char hw[MAX_HWADDR_LEN] = {0};
    char mac_str[3 * MAX_HWADDR_LEN] = {0};
    bool ok = name && keyfile && hw_len == MAX_HWADDR_LEN;
    if (ok) {
        (*env)->GetByteArrayRegion(env, jhw, 0, MAX_HWADDR_LEN, (jbyte *) hw);
        snprintf(mac_str, sizeof(mac_str), "%02x:%02x:%02x:%02x:%02x:%02x",
                 (unsigned char) hw[0], (unsigned char) hw[1], (unsigned char) hw[2],
                 (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);
    }

    if (ok) {
        b->raop = raop_init(&cbs);
        ok = b->raop != NULL;
    }
    if (ok) {
        raop_set_log_callback(b->raop, log_cb, b);
        raop_set_log_level(b->raop, log_level);
        /* nohold = 1: a new client may take over from the current one */
        ok = raop_init2(b->raop, 1, mac_str, keyfile) == 0;
    }
    if (ok) {
        if (width > 0) raop_set_plist(b->raop, "width", width);
        if (height > 0) raop_set_plist(b->raop, "height", height);
        if (fps > 0) {
            raop_set_plist(b->raop, "refreshRate", fps);
            raop_set_plist(b->raop, "maxFPS", fps);
        }
        if (pin_mode == 1) {
            /* values > 9999 mean "fixed PIN" (pin % 10000); otherwise a random PIN per pairing */
            raop_set_plist(b->raop, "pin", pin > 0 ? 10000 + (pin % 10000) : 1);
        }
        if (hls) raop_set_plist(b->raop, "hls", 1);

        int err = 0;
        b->dnssd = dnssd_init(name, (int) strlen(name), hw, MAX_HWADDR_LEN, (unsigned char) pin_mode, &err);
        ok = b->dnssd && err == 0;
    }
    if (ok) {
        dnssd_set_airplay_features(b->dnssd, 0, hls ? 1 : 0);   /* AirPlay video */
        dnssd_set_airplay_features(b->dnssd, 4, hls ? 1 : 0);   /* HLS */
        dnssd_set_airplay_features(b->dnssd, 27, 1);            /* legacy pairing (PIN support) */
        dnssd_set_airplay_features(b->dnssd, 42, h265 ? 1 : 0); /* H.265 mirroring */
    }
    if (name) (*env)->ReleaseStringUTFChars(env, jname, name);
    if (keyfile) (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);

    if (!ok) {
        LOGE("receiver init failed");
        bridge_free(env, b);
        return 0;
    }
    g_bridge = b;
    airplaytv_accept_filter = accept_filter;
    return (jlong) (intptr_t) b;
}

JNIEXPORT jint JNICALL
Java_com_airplaytv_airplay_NativeBridge_nativeStart(JNIEnv *env, jclass clazz, jlong handle, jint port) {
    bridge_t *b = (bridge_t *) (intptr_t) handle;
    if (!b || !b->raop) return -1;
    unsigned short tcp[2] = { (unsigned short) port, (unsigned short) port };
    unsigned short udp[3] = { 0, 0, 0 };
    raop_set_tcp_ports(b->raop, tcp);
    raop_set_udp_ports(b->raop, udp);
    unsigned short p = raop_get_port(b->raop);
    if (raop_start_httpd(b->raop, &p) < 0) return -2;
    raop_set_port(b->raop, p);
    raop_set_dnssd(b->raop, b->dnssd);    /* also hands the public key to dnssd */
    if (dnssd_register_raop(b->dnssd, p) != 0) return -3;
    if (dnssd_register_airplay(b->dnssd, p) != 0) return -4;
    b->running = true;
    return p;
}

JNIEXPORT jobjectArray JNICALL
Java_com_airplaytv_airplay_NativeBridge_nativeGetService(JNIEnv *env, jclass clazz, jlong handle, jint service) {
    bridge_t *b = (bridge_t *) (intptr_t) handle;
    if (!b || !b->dnssd) return NULL;
    dnssd_android_service_t s;
    if (dnssd_android_get_service(b->dnssd, service, &s) != 0) return NULL;
    jclass strc = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = (*env)->NewObjectArray(env, 3 + 2 * s.count, strc, NULL);
    if (!arr) return NULL;
    char port[8];
    snprintf(port, sizeof(port), "%u", s.port);
    const char *head[3] = { s.name, s.type, port };
    for (int i = 0; i < 3; i++) {
        jstring v = (*env)->NewStringUTF(env, head[i]);
        (*env)->SetObjectArrayElement(env, arr, i, v);
        (*env)->DeleteLocalRef(env, v);
    }
    for (int i = 0; i < s.count; i++) {
        jstring k = (*env)->NewStringUTF(env, s.keys[i]);
        jstring v = (*env)->NewStringUTF(env, s.values[i]);
        (*env)->SetObjectArrayElement(env, arr, 3 + 2 * i, k);
        (*env)->SetObjectArrayElement(env, arr, 4 + 2 * i, v);
        (*env)->DeleteLocalRef(env, k);
        (*env)->DeleteLocalRef(env, v);
    }
    return arr;
}

/* Drops every client connection and restarts the listener on the same port.
   Must not be called from a UxPlay thread (raop_stop_httpd joins the httpd thread). */
JNIEXPORT jint JNICALL
Java_com_airplaytv_airplay_NativeBridge_nativeResetConnections(JNIEnv *env, jclass clazz, jlong handle) {
    bridge_t *b = (bridge_t *) (intptr_t) handle;
    if (!b || !b->raop) return -1;
    pthread_mutex_lock(&b->lock);
    unsigned short p = raop_get_port(b->raop);
    raop_stop_httpd(b->raop);
    raop_destroy_airplay_video(b->raop, -1);
    raop_remove_known_connections(b->raop);
    int r = raop_start_httpd(b->raop, &p);
    if (r >= 0) raop_set_port(b->raop, p);
    pthread_mutex_unlock(&b->lock);
    return r < 0 ? -2 : p;
}

JNIEXPORT void JNICALL
Java_com_airplaytv_airplay_NativeBridge_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    bridge_t *b = (bridge_t *) (intptr_t) handle;
    if (!b) return;
    airplaytv_accept_filter = NULL;
    if (b->raop) raop_stop_httpd(b->raop);
    g_bridge = NULL;
    bridge_free(env, b);
}
