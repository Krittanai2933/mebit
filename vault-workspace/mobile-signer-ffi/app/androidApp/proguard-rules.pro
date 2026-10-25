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

# Looked up from native code (btleplug's JNI_OnLoad init, JNA's callbacks): renaming breaks them.
-keep class com.nonpolynomial.** { *; }
-keep class io.github.gedgygedgy.** { *; }
-keep class com.sun.jna.** { *; }
-keep class com.mebit.ffi.** { *; }

# JNA's desktop-only AWT helpers name classes Android does not have.
-dontwarn java.awt.**
