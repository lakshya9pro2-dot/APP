# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }

# Keep our app classes
-keep class com.liteweb.extractor.** { *; }

# Standard Android
-keepattributes Signature
-keepattributes *Annotation*

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
