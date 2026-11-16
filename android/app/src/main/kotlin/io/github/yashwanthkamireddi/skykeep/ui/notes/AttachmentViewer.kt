package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.yashwanthkamireddi.skykeep.R
import io.github.yashwanthkamireddi.skykeep.sync.DriveFiles
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.quince_mobile.AccountInterface

/** A tapped attachment: the page's `host_open_attachment` (BRIDGE.md, Events). */
data class OpenedAttachment(val id: String, val kind: String, val name: String) {
  /** A picture or drawing, shown here full screen; anything else opens in its app. */
  val shownHere: Boolean get() = kind == "image" || kind == "drawing"
}

/** The longest side a picture is decoded at: sharp when zoomed, never a phone's whole memory. */
private const val MOST = 4096

/**
 * A note's picture or drawing full screen, on black, as the Photos viewer shows one: pinch to
 * zoom (and drag while zoomed), double-tap to zoom in or back out, Close at the top. The bytes are
 * the ones the page draws (NoteMedia: an iPhone's HEIC made a JPEG, Apple's preview when Android
 * can't decode it); a drawing is drawn in the theme's own version.
 */
@Composable
fun AttachmentViewer(engine: AccountInterface, attachment: OpenedAttachment, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val dark = isSystemInDarkTheme()
  var picture by remember(attachment) { mutableStateOf<Bitmap?>(null) }
  var failed by remember(attachment) { mutableStateOf(false) }
  LaunchedEffect(attachment, dark) {
    val parts = if (attachment.kind == "drawing") listOf(if (dark) "drawing-dark" else "drawing", "drawing", "preview") else listOf("file", "preview")
    picture = withContext(Dispatchers.IO) { picture(engine, context.cacheDir, attachment.id, parts) }
    failed = picture == null
  }
  var scale by remember(attachment) { mutableFloatStateOf(1f) }
  var offset by remember(attachment) { mutableStateOf(Offset.Zero) }
  val name = attachment.name.ifBlank { if (attachment.kind == "drawing") "Drawing" else "Picture" }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("attachment-viewer")) {
      val shown = picture
      when {
        shown != null -> Image(
          bitmap = shown.asImageBitmap(),
          contentDescription = name,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(attachment) {
              detectTapGestures(onDoubleTap = { at ->
                if (scale > 1f) {
                  scale = 1f; offset = Offset.Zero
                } else {
                  // In on the place tapped: it stays under the finger.
                  scale = 2.5f
                  offset = (Offset(size.width / 2f, size.height / 2f) - at) * 1.5f
                }
              })
            }
            .pointerInput(attachment) {
              detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else {
                  // Never dragged off the screen: at most the picture's overhang either way.
                  val maxX = size.width * (scale - 1f) / 2f
                  val maxY = size.height * (scale - 1f) / 2f
                  val next = offset + pan
                  Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                }
              }
            }
            .graphicsLayer {
              scaleX = scale; scaleY = scale
              translationX = offset.x; translationY = offset.y
            },
        )
        failed -> Text(
          "This picture can't be shown. It may not have downloaded yet.",
          color = Color.White, style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.align(Alignment.Center).padding(32.dp),
        )
        else -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
      }
      Row(
        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).safeDrawingPadding().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onDismiss) {
          Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      }
    }
  }
}

/** The first of `parts` Android can draw, decoded at most [MOST] a side; null when none can be. */
private fun picture(engine: AccountInterface, cacheDir: File, id: String, parts: List<String>): Bitmap? {
  val media = NoteMedia(engine, cacheDir)
  for (part in parts.distinct()) {
    val response = media.handle("/${Uri.encode(id)}/$part") ?: continue
    if (response.statusCode != 200) continue
    val bytes = runCatching { response.data.readBytes() }.getOrNull() ?: continue
    val bitmap = runCatching {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val side = maxOf(info.size.width, info.size.height)
        if (side > MOST) decoder.setTargetSize(info.size.width * MOST / side, info.size.height * MOST / side)
      }
    }.getOrNull()
    if (bitmap != null) return bitmap
  }
  return null
}

/**
 * A file in a note opened in the phone's app for its kind: its bytes copied into the cache's
 * share folder under its own name and handed over for reading only. False when the file isn't
 * held (offline, not downloaded) or no app on the phone opens it, with `onProblem` told why.
 */
suspend fun openAttachmentFile(context: Context, engine: AccountInterface, attachment: OpenedAttachment, onProblem: (String) -> Unit): Boolean {
  val ready = withContext(Dispatchers.IO) {
    runCatching {
      val info = runCatching { JSONObject(engine.noteAttachmentInfoJson(attachment.id)) }.getOrNull()
      val named = attachment.name.ifBlank { info?.optString("filename").orEmpty() }.ifBlank { info?.optString("title").orEmpty() }
      val name = DriveFiles.safeName(named.ifBlank { "Attachment" })
      val bytes = engine.noteMedia(attachment.id, "file")
      if (bytes.isEmpty()) return@runCatching null
      val file = File(context.cacheDir, "share/${DriveFiles.keyOf(attachment.id)}/$name").also { it.parentFile?.mkdirs() }
      file.writeBytes(bytes)
      val type = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "application/octet-stream"
      FileProvider.getUriForFile(context, "${context.packageName}.share", file) to type
    }.getOrNull()
  }
  if (ready == null) {
    onProblem("This file isn't on the phone yet. Try again when you're online.")
    return false
  }
  val (uri, type) = ready
  val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
  return try {
    context.startActivity(view)
    true
  } catch (_: ActivityNotFoundException) {
    onProblem("No app on this phone opens this kind of file.")
    false
  }
}
