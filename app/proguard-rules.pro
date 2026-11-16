# Proguard rules for HyperAlive
-keep class com.hyperalive.aod.XposedInit { *; }
-keep class com.hyperalive.aod.** { *; }
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
