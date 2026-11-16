package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import uniffi.quince_mobile.AccountInterface

/**
 * A note's pictures, drawings and files as the page asks for them: `note-media/<id>/<part>`
 * (BRIDGE.md). The bytes are the engine's, kept by it under the app's own data directory; what
 * the page cannot draw is made drawable here — an iPhone's HEIC becomes a JPEG once, kept in the
 * cache — and Apple's own rendering stands in when Android cannot decode it.
 *
 * Nothing is fetched for a part the page never asks for, and an attachment with nothing to show
 * is a 404: the page draws the file's card in the picture's place rather than a broken image.
 */
internal class NoteMedia(
  private val engine: AccountInterface,
  cacheDir: File,
) : WebViewAssetLoader.PathHandler {
  /** The JPEGs made from pictures the page cannot draw, by attachment and part. */
  private val shown = File(cacheDir, "note-shown").apply { mkdirs() }

  override fun handle(path: String): WebResourceResponse? {
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.size != 2) return notFound()
    val id = runCatching { Uri.decode(parts[0]) }.getOrNull().orEmpty()
    val part = parts[1]
    if (id.isEmpty() || part !in PARTS) return notFound()
    return runCatching {
      val made = File(shown, "${safe(id)}-${safe(part)}.jpg")
      if (made.isFile) return served(made.readBytes(), "image/jpeg")
      val bytes = engine.noteMedia(id, part)
      if (isHeif(bytes)) {
        val jpeg = toJpeg(bytes)
        if (jpeg != null) {
          runCatching { made.writeBytes(jpeg) }
          return served(jpeg, "image/jpeg")
        }
        // Android could not draw this one: Apple keeps a rendering of every picture for this.
        val preview = runCatching { engine.noteMedia(id, "preview") }.getOrNull()
        if (preview != null) return served(preview, contentType(preview))
      }
      served(bytes, contentType(bytes))
    }.getOrNull() ?: notFound()
  }

  private fun served(bytes: ByteArray, type: String): WebResourceResponse =
    WebResourceResponse(type, null, 200, "OK", mapOf("Cache-Control" to "max-age=31536000"), ByteArrayInputStream(bytes))

  private fun notFound(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

  /** A HEIC (or any ISO base media picture): the page's WebView draws JPEG and PNG, not this. */
  private fun isHeif(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    if (bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) != "ftyp") return false
    return bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) in HEIF_BRANDS
  }

  /** One picture as a JPEG the page can draw; null when Android has no decoder for it. */
  private fun toJpeg(bytes: ByteArray): ByteArray? = runCatching {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    out.toByteArray()
  }.getOrNull()

  /** What a file is, read from its own first bytes, as the desktop reads it. */
  private fun contentType(bytes: ByteArray): String {
    fun at(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
    return when {
      at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "image/png"
      at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
      at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code -> "image/gif"
      at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
        at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "image/webp"
      isHeif(bytes) -> "image/heic"
      at(0) == '%'.code && at(1) == 'P'.code && at(2) == 'D'.code && at(3) == 'F'.code -> "application/pdf"
      else -> "application/octet-stream"
    }
  }

  /** An attachment id as a file name: only what a file system takes, as the desktop does it. */
  private fun safe(id: String): String = id.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

  private companion object {
    /** The parts the page asks for (BRIDGE.md). */
    val PARTS = setOf("file", "inline", "preview", "drawing", "drawing-dark", "thumb")

    /** HEIF's brands: an iPhone's picture, and the generic ones libheif writes. */
    val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1")
  }
}
