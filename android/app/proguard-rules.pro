-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# WebView bridge for the admin panel tab (window.print)
-keepclassmembers class com.tqii.liveclass.PrintBridge {
    @android.webkit.JavascriptInterface <methods>;
}
