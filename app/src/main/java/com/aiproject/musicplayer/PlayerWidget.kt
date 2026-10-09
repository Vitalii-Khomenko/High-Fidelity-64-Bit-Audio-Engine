package com.aiproject.musicplayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import androidx.media.session.MediaButtonReceiver
import com.aiproject.musicplayer.playback.PlayerStore

/**
 * Home-screen player: cover, title, artist and previous / play-pause / next.
 * The buttons send media-button intents, so they work with the app closed;
 * the service pushes updates while it runs.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Not playing (the service updates the widget itself): show what would resume.
        val saved = runCatching { PlayerStore(context).loadQueue() }.getOrNull()
        val track = saved?.tracks?.getOrNull(saved.index)
        manager.updateAppWidget(ids, views(context, track?.title, track?.artist?.ifBlank { track.folder }, playing = false, cover = null))
    }

    companion object {
        private var last: List<Any?> = emptyList()

        /** Pushes the player state to all widgets (cheap no-op when nothing changed). */
        fun update(context: Context, title: String?, subtitle: String?, playing: Boolean, cover: Bitmap?) {
            val key = listOf(title, subtitle, playing, cover?.let { System.identityHashCode(it) })
            if (key == last) return
            last = key
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            runCatching { manager.updateAppWidget(ids, views(context, title, subtitle, playing, cover)) }
        }

        private fun views(context: Context, title: String?, subtitle: String?, playing: Boolean, cover: Bitmap?): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_player).apply {
                setTextViewText(R.id.widget_title, title ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_subtitle, subtitle.orEmpty())
                if (cover != null) setImageViewBitmap(R.id.widget_cover, cover) else setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.action_pause else R.string.action_play))
                setOnClickPendingIntent(R.id.widget_previous, button(context, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_play, button(context, PlaybackStateCompat.ACTION_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, button(context, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }

        private fun button(context: Context, action: Long): PendingIntent =
            MediaButtonReceiver.buildMediaButtonPendingIntent(context, action)
    }
}
