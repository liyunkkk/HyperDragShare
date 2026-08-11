# Xposed entry points are loaded by class name from assets/xposed_init.
-keep class com.leaf.hyperdragshare.codex.MainHook { *; }

# cppjieba registers these methods by their Java class and method names in JNI_OnLoad.
-keep class com.leaf.hyperdragshare.codex.TextSegmenter {
    native <methods>;
}

# ML Kit bundled text recognition loads resources and keeps internal state
# by reflection; the bundled model classes must survive minification.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
