# GameActivity's lifecycle is called by android-activity's JNI glue.
-keep class com.google.androidgamesdk.** { *; }
-keep class dev.horizon.android.HorizonGameActivity { *; }
-keepclasseswithmembernames class * { native <methods>; }
