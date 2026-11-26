# Proguard rules for HyperAlive
-keep class io.github.askt_clot.hyperalive.XposedInit { *; }
-keep class io.github.askt_clot.hyperalive.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
