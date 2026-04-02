# Preserve Android entry points and Room metadata used by the app at runtime.
-keep class com.aiproject.musicplayer.MainActivity { *; }
-keep class com.aiproject.musicplayer.PlaybackService { *; }
-keep class com.aiproject.musicplayer.db.** { *; }
-keep class * extends android.app.Service
-keep class * extends androidx.room.RoomDatabase
-keepattributes *Annotation*