# --- Preserve main application classes ---
-keep class com.hotsharefile.hotsharefile.** { *; }

# --- Preserve interfaces for inter-thread communication ---
-keep interface com.hotsharefile.hotsharefile.ProgInterface { *; }

# --- Preserve inner classes for HTTP server ---
-keepclassmembers class com.hotsharefile.hotsharefile.SimpleHttpServer$* { *; }

# --- Preserve reflection methods ---
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Prevent removal of Assets code ---
-keep class com.hotsharefile.hotsharefile.QR { *; }
-keep class com.hotsharefile.hotsharefile.SimpleHttpServer { *; }

# --- General optimization rules ---
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# --- Remove debug logs in the release version ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}