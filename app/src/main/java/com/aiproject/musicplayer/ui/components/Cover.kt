package com.aiproject.musicplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiproject.musicplayer.library.CoverArt
import com.aiproject.musicplayer.ui.theme.Aw

/**
 * Square cover of a track (embedded picture or folder picture). While loading,
 * and when there is none, a pixel mark on ink stands in, tinted by [seed] so
 * neighbouring albums do not look identical.
 */
@Composable
fun Cover(trackUri: String?, size: Dp, modifier: Modifier = Modifier, seed: String = trackUri.orEmpty()) {
    val aw = Aw.colors
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(64, CoverArt.MAX_PX)
    val image by produceState<ImageBitmap?>(null, trackUri, px) {
        value = if (preview || trackUri == null) null
        else runCatching { CoverArt.load(context, trackUri, px)?.asImageBitmap() }.getOrNull()
    }
    Box(modifier.then(Modifier.background(aw.raised).border(1.dp, aw.line))) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val tones = listOf(aw.violet, aw.amber, aw.cyan)
            val tone = tones[(seed.hashCode() and 0x7fffffff) % tones.size]
            Canvas(Modifier.fillMaxSize()) {
                val u = this.size.width / 20f
                fun cell(x: Int, y: Int, s: Int, a: Float) = drawRect(tone.copy(alpha = a), Offset(x * u, y * u), Size(s * u, s * u))
                cell(3, 13, 4, 0.9f)
                cell(8, 9, 3, 0.6f)
                cell(12, 6, 2, 0.4f)
                cell(15, 4, 1, 0.25f)
            }
        }
    }
}
