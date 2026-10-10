# JNI: the native library binds to these exact class and method names.
-keep class com.aiproject.musicplayer.AudioEngine { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }
# Called from native code by name (src/jni/audio_engine_jni.cpp).
-keep interface com.aiproject.musicplayer.DirectOutputPolicy { *; }
-keepclassmembers class * implements com.aiproject.musicplayer.DirectOutputPolicy { int encodingFor(int, int); }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.aiproject.musicplayer.db.** { *; }
-keepattributes *Annotation*
