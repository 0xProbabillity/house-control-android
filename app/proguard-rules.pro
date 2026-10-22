# The page calls the bridge by name.
-keepclassmembers class dev.housecontrol.android.web.HomeWeb$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# BouncyCastle's TLS looks up its algorithms reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# OkHttp's optional platforms.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Glance finds a widget's ids by its class: three look-alike widgets must stay three classes
# (R8 would otherwise merge them, and every widget would draw the same one).
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
-keep class * implements androidx.glance.appwidget.action.ActionCallback
