# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep AIDL interfaces
-keep interface moe.shizuku.privileged.api.** { *; }
