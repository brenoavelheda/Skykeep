package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.quince_mobile.AccountInterface

/** The side of a row's small picture, as Notes draws it at the end of the row. */
private val THUMB = 44.dp

/** Each note's first picture this phone holds, by note guid, from the engine's `notePicturesJson`. */
fun notePictures(json: String): Map<String, String> {
  val o = JSONObject(json)
  return o.keys().asSequence().associateWith { o.getString(it) }
}

/** A picture decoded for the list, or none: one that would not decode is not tried again. */
private class Kept(val picture: ImageBitmap?)

/**
 * The list's pictures already decoded, by attachment id, so scrolling back up draws them at
 * once. Bounded by their bytes; a missing one costs next to nothing.
 */
private val kept = object : LruCache<String, Kept>(8 * 1024 * 1024) {
  override fun sizeOf(key: String, value: Kept) = value.picture?.let { it.width * it.height * 4 } ?: 1
}

/** The attachment's small picture from what the phone already holds, decoded near `px` square. */
private fun decode(engine: AccountInterface, id: String, px: Int): Kept = Kept(
  runCatching {
    val bytes = engine.noteMedia(id, "thumb")
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
      // Decoded at the smallest power-of-two scale still covering the row's square: an
      // iPhone photo is millions of pixels, the row a few thousand.
      var sample = 1
      while (minOf(info.size.width, info.size.height) / (sample * 2) >= px) sample *= 2
      decoder.setTargetSampleSize(sample)
    }.asImageBitmap()
  }.getOrNull(),
)

/**
 * A note's first picture at the end of its row: read from what the phone already holds (nothing
 * is fetched for the list), decoded off the main thread, kept in memory. Its place is kept while
 * it loads, so the row's text does not jump when it comes; nothing is drawn if it cannot be read.
 */
@Composable
fun NoteThumb(engine: AccountInterface, id: String, modifier: Modifier = Modifier) {
  val px = with(LocalDensity.current) { THUMB.roundToPx() }
  var picture by remember(id) { mutableStateOf(kept.get(id)) }
  LaunchedEffect(id) {
    if (picture == null) picture = withContext(Dispatchers.IO) { decode(engine, id, px) }.also { kept.put(id, it) }
  }
  val shape = RoundedCornerShape(6.dp)
  Box(modifier.size(THUMB)) {
    picture?.picture?.let {
      Image(
        it, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize().clip(shape).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
      )
    }
  }
}
