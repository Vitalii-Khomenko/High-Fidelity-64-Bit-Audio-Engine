# JNI: the native library binds to these exact class and method names.
-keep class com.aiproject.musicplayer.AudioEngine { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.aiproject.musicplayer.db.** { *; }
-keepattributes *Annotation*
