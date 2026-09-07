# mpv-android 相关（实际包名为 dev.jdtech.mpv）
-keep class dev.jdtech.mpv.** { *; }
-dontwarn dev.jdtech.mpv.**
-keepclassmembers class dev.jdtech.mpv.** { *; }

# MPVLib（JNI 桥接类，禁止混淆）
-keep class dev.jdtech.mpv.MPVLib { *; }

# 项目自定义播放器组件
-keep class com.fntv.app.CustomMPVView { *; }
-keep class com.fntv.app.Anime4KManager { *; }
-keep class com.fntv.app.SubtitleManager { *; }
-keep class com.fntv.app.MPVEventObserver { *; }
-keep interface com.fntv.app.MPVTimeSource { *; }
