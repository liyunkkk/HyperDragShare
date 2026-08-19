# libxposed entry is loaded reflectively by class name from
# META-INF/xposed/java_init.list; keep the whole class including the public
# no-arg constructor and the lifecycle callbacks the framework invokes.
-keep class com.leaf.hyperdragshare.codex.MainHook { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# cppjieba registers these methods by their Java class and method names in JNI_OnLoad.
-keep class com.leaf.hyperdragshare.codex.TextSegmenter {
    native <methods>;
}

# ML Kit bundled text recognition loads resources and keeps internal state
# by reflection; the bundled model classes must survive minification.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
