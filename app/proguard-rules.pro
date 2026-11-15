# JNI callbacks are looked up by name from native code.
-keep class com.airplaytv.airplay.NativeCallbacks { *; }
-keep class com.airplaytv.airplay.NativeBridge { *; }
# JmDNS uses reflection-free code but references optional slf4j bindings.
-dontwarn org.slf4j.**
-dontwarn javax.jmdns.**
-keep class javax.jmdns.** { *; }
