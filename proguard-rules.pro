-keep class com.exam.app.models.** { *; }
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.apache.**
-keepclassmembers class com.exam.app.ui.** {
    @android.webkit.JavascriptInterface <methods>;
}
