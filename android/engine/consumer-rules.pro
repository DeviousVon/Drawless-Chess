# JNI_OnLoad registers this exact class and its static method names.
-keep class com.drawlesschess.engine.FairyNativeBindings {
    public static native <methods>;
}
