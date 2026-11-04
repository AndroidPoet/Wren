# Room, Hilt, Retrofit and Moshi ship their own consumer rules; these cover the
# reflective corners the app itself relies on.

# Models are read reflectively by the JSON adapters.
-keep class dev.androidpoet.wren.core.model.** { *; }

# The WebView JS bridge is called by name from injected JavaScript.
-keepclassmembers class * {
  @android.webkit.JavascriptInterface <methods>;
}

# Tor's native library reads and writes TorService fields by name over JNI, and tor-android ships
# no consumer rules. Renamed or stripped, the tor thread dies with NoSuchFieldError the moment
# Tor is switched on, and again on every launch after, since the setting is remembered.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }

# Parcelize creators.
-keepclassmembers class * implements android.os.Parcelable {
  public static final ** CREATOR;
}

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
