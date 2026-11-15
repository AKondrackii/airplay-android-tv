package com.airplaytv.airplay

/** JNI entry points of libairplaytv.so (see src/main/cpp/airplay_jni.c). */
internal object NativeBridge {
    const val SERVICE_RAOP = 0
    const val SERVICE_AIRPLAY = 1

    init {
        System.loadLibrary("airplaytv")
    }

    @JvmStatic external fun nativeCreate(
        callbacks: NativeCallbacks,
        name: String,
        hwAddr: ByteArray,
        keyFile: String,
        pinMode: Int,
        pin: Int,
        password: ByteArray?,
        width: Int,
        height: Int,
        fps: Int,
        hls: Boolean,
        h265: Boolean,
        logLevel: Int,
    ): Long

    /** Starts the RTSP/HTTP listener. Returns the bound TCP port or a negative error. */
    @JvmStatic external fun nativeStart(handle: Long, port: Int): Int

    /** [instanceName, serviceType, port, key1, value1, key2, value2, ...] or null. */
    @JvmStatic external fun nativeGetService(handle: Long, service: Int): Array<String>?

    /** Drops all client connections and restarts the listener. Never call from a native callback thread. */
    @JvmStatic external fun nativeResetConnections(handle: Long): Int

    @JvmStatic external fun nativeDestroy(handle: Long)
}
