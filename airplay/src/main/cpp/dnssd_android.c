/*
 * AirPlay TV — Android backend for UxPlay's dnssd.h interface.
 *
 * UxPlay normally publishes its Bonjour services through Avahi / mDNSResponder.
 * Android has neither, so this backend only *builds* the RAOP and AirPlay TXT
 * records (same keys and values as UxPlay's dns_sd.c backend). The Kotlin layer
 * reads them back through the JNI bridge and publishes them with JmDNS bound to
 * the allowed network interface only.
 *
 * The TXT records are also served in the /info response by raop_handlers.h,
 * which is why they are kept in DNS wire format here.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "third_party/uxplay/dnssd.h"
#include "third_party/uxplay/dnssdint.h"
#include "third_party/uxplay/global.h"
#include "third_party/uxplay/utils.h"
#include "dnssd_android.h"

#define TXT_MAX 1024
#define MAX_SERVNAME 256

typedef struct txt_record_s {
    unsigned char wire[TXT_MAX];
    int wire_len;
    int count;
    char keys[DNSSD_ANDROID_MAX_TXT][32];
    char values[DNSSD_ANDROID_MAX_TXT][256];
    char service_name[MAX_SERVNAME];
    unsigned short port;
    int registered;
} txt_record_t;

typedef struct dnssd_private_s {
    txt_record_t raop;
    txt_record_t airplay;
} dnssd_private_t;

static void txt_reset(txt_record_t *txt) {
    memset(txt, 0, sizeof(*txt));
}

static void txt_set(txt_record_t *txt, const char *key, const char *value) {
    size_t klen = strlen(key);
    size_t vlen = strlen(value);
    if (txt->count >= DNSSD_ANDROID_MAX_TXT) return;
    if (klen >= sizeof(txt->keys[0]) || vlen >= sizeof(txt->values[0])) return;
    /* one length byte + "key=value", each entry at most 255 bytes */
    if (klen + 1 + vlen > 255) return;
    if (txt->wire_len + 1 + (int) (klen + 1 + vlen) > TXT_MAX) return;

    unsigned char *p = txt->wire + txt->wire_len;
    *p++ = (unsigned char) (klen + 1 + vlen);
    memcpy(p, key, klen); p += klen;
    *p++ = '=';
    memcpy(p, value, vlen);
    txt->wire_len += (int) (1 + klen + 1 + vlen);

    memcpy(txt->keys[txt->count], key, klen + 1);
    memcpy(txt->values[txt->count], value, vlen + 1);
    txt->count++;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_private_t *priv = calloc(1, sizeof(dnssd_private_t));
    if (!priv && error) *error = DNSSD_ERROR_OUTOFMEM;
    return priv;
}

void dnssd_private_destroy(void *priv) {
    free(priv);
}

void dnssd_error_text(int *error, const char *appname) {
    (void) error; (void) appname;
}

int dnssd_register_raop(dnssd_t *d, unsigned short port) {
    if (!d || !d->dnssd_private || !d->pk) return -1;
    dnssd_private_t *priv = d->dnssd_private;
    txt_record_t *t = &priv->raop;
    char features[24];
    char servname[MAX_SERVNAME];

    txt_reset(t);
    snprintf(features, sizeof(features), "0x%X,0x%X", d->features1, d->features2);

    txt_set(t, "ch", RAOP_CH);
    txt_set(t, "cn", RAOP_CN);
    txt_set(t, "da", RAOP_DA);
    txt_set(t, "et", RAOP_ET);
    txt_set(t, "vv", RAOP_VV);
    txt_set(t, "ft", features);
    txt_set(t, "am", GLOBAL_MODEL);
    txt_set(t, "md", RAOP_MD);
    txt_set(t, "rhd", RAOP_RHD);
    switch (d->pin_pw) {
    case 1:
        txt_set(t, "pw", "true");
        txt_set(t, "sf", "0x8c");
        break;
    case 2:
    case 3:
        txt_set(t, "pw", "true");
        txt_set(t, "sf", "0x84");
        break;
    default:
        txt_set(t, "pw", "false");
        txt_set(t, "sf", RAOP_SF);
        break;
    }
    txt_set(t, "sr", RAOP_SR);
    txt_set(t, "ss", RAOP_SS);
    txt_set(t, "sv", RAOP_SV);
    txt_set(t, "tp", RAOP_TP);
    txt_set(t, "txtvers", RAOP_TXTVERS);
    txt_set(t, "vs", RAOP_VS);
    txt_set(t, "vn", RAOP_VN);
    txt_set(t, "pk", d->pk);

    if (utils_hwaddr_raop(servname, sizeof(servname), d->hw_addr, d->hw_addr_len) < 0) return -1;
    if (sizeof(servname) < strlen(servname) + 1 + (size_t) d->name_len + 1) return -2;
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, d->name, sizeof(servname) - strlen(servname) - 1);
    snprintf(t->service_name, sizeof(t->service_name), "%s", servname);
    t->port = port;
    t->registered = 1;
    return 0;
}

int dnssd_register_airplay(dnssd_t *d, unsigned short port) {
    if (!d || !d->dnssd_private || !d->pk) return -1;
    dnssd_private_t *priv = d->dnssd_private;
    txt_record_t *t = &priv->airplay;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[24];

    txt_reset(t);
    snprintf(features, sizeof(features), "0x%X,0x%X", d->features1, d->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), d->hw_addr, d->hw_addr_len) < 0) return -1;

    txt_set(t, "deviceid", device_id);
    txt_set(t, "features", features);
    txt_set(t, "pw", d->pin_pw ? "true" : "false");
    txt_set(t, "flags", "0x4");
    txt_set(t, "model", GLOBAL_MODEL);
    txt_set(t, "pk", d->pk);
    txt_set(t, "pi", AIRPLAY_PI);
    txt_set(t, "srcvers", AIRPLAY_SRCVERS);
    txt_set(t, "vv", AIRPLAY_VV);

    snprintf(t->service_name, sizeof(t->service_name), "%s", d->name);
    t->port = port;
    t->registered = 1;
    return 0;
}

const char *dnssd_get_raop_txt(dnssd_t *d, int *length) {
    dnssd_private_t *priv = d->dnssd_private;
    *length = priv->raop.wire_len;
    return (const char *) priv->raop.wire;
}

const char *dnssd_get_airplay_txt(dnssd_t *d, int *length) {
    dnssd_private_t *priv = d->dnssd_private;
    *length = priv->airplay.wire_len;
    return (const char *) priv->airplay.wire;
}

void dnssd_unregister_raop(dnssd_t *d) {
    if (d && d->dnssd_private) ((dnssd_private_t *) d->dnssd_private)->raop.registered = 0;
}

void dnssd_unregister_airplay(dnssd_t *d) {
    if (d && d->dnssd_private) ((dnssd_private_t *) d->dnssd_private)->airplay.registered = 0;
}

int dnssd_get_service_fd(dnssd_t *d, int service) {
    (void) d; (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *d, int service) {
    (void) d; (void) service;
    return 0;
}

int dnssd_android_get_service(dnssd_t *d, int service, dnssd_android_service_t *out) {
    if (!d || !d->dnssd_private || !out) return -1;
    dnssd_private_t *priv = d->dnssd_private;
    txt_record_t *t = (service == DNSSD_SERVICE_RAOP) ? &priv->raop : &priv->airplay;
    if (!t->registered) return -1;
    out->name = t->service_name;
    out->type = (service == DNSSD_SERVICE_RAOP) ? "_raop._tcp" : "_airplay._tcp";
    out->port = t->port;
    out->count = t->count;
    for (int i = 0; i < t->count; i++) {
        out->keys[i] = t->keys[i];
        out->values[i] = t->values[i];
    }
    return 0;
}
