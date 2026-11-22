# Métodos chamados por nome a partir do código nativo (JNI)
-keep class com.conversorchd.nativebridge.NativeChd { *; }
-keep interface com.conversorchd.nativebridge.NativeListener { *; }
-keepclassmembers class * implements com.conversorchd.nativebridge.NativeListener {
    public void onProgress(int, double, double, long, long);
    public void onLog(java.lang.String);
}
-keepclasseswithmembernames class * {
    native <methods>;
}
