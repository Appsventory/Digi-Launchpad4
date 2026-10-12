# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ─── Digi Home: aturan R8 untuk build release (isMinifyEnabled = true) ───

# Simpan info baris supaya stack trace crash release tetap terbaca (mis. via Play Console
# atau logcat), tanpa membocorkan nama file asli.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Model data yang dipakai (org.json manual parsing, bukan reflection-based seperti Gson),
# tapi tetap di-keep agar nama properti aman jika suatu saat dipakai reflectively/serialisasi.
-keep class com.appsventory.digiapps.launchpad4.Recommendation { *; }
-keep class com.appsventory.digiapps.launchpad4.MediaProvider { *; }
-keep class com.appsventory.digiapps.launchpad4.MainActivity$JellyfinLibrary { *; }

# org.json sudah bagian dari Android SDK (tidak perlu di-shrink/obfuscate lagi),
# tapi aman kalau ada referensi lewat classloader.
-dontwarn org.json.**

# BroadcastReceiver anonim & listener yang direferensikan lewat XML (mis. via findViewById
# + reflection Android internal untuk android:onClick) sudah otomatis di-keep oleh
# consumer rules AGP; tidak butuh aturan tambahan karena semua listener di-set programatik.
# ─── smbj (hierynomus) ───
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-keep class net.engio.mbassy.** { *; }
-dontwarn net.engio.mbassy.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
