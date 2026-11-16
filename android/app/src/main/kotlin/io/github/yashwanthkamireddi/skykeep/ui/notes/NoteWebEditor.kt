package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import uniffi.quince_mobile.AccountInterface

/** The origin `androidx.webkit`'s asset loader answers from, and the page on it (BRIDGE.md). */
private const val ORIGIN = "https://appassets.androidplatform.net"
private const val PAGE = "$ORIGIN/assets/notes-editor/index.html"

/** The object the page posts its commands to. */
private const val BRIDGE = "SkykeepHost"

/** The schemes the page may hand to Android when a link is tapped (BRIDGE.md). */
private val OPEN_SCHEMES = setOf("http", "https", "mailto", "tel", "geo", "applenotes")

/**
 * One note, read and edited on the page the desktop's own editor draws (src/mobile/notes): a
 * WebView on the app's bundled assets, served from `https://appassets.androidplatform.net` so
 * there is no file access at all. Everything the page asks for goes through [NoteHost] to the
 * engine; every request to another origin is refused, and every navigation away from the page
 * is cancelled — a link the person tapped is handed to Android instead.
 *
 * The page keeps the save session; this asks it to flush when the app stops and when the note is
 * left (Notes.kt), and the page saves by itself when it is hidden or torn down.
 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
fun NoteWebEditor(
  engine: AccountInterface,
  state: NoteEditorState,
  guid: String?,
  folderGuid: String?,
  editable: Boolean,
  locked: Boolean,
  found: List<String>,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val theme = if (isSystemInDarkTheme()) "dark" else "light"
  val ready by state.ready.collectAsState()

  val page = remember(state, engine) {
    NotePageView(context, engine, state) { href -> openOutside(context, href) }
  }
  DisposableEffect(page) {
    state.page = page
    state.resetReady()
    val observer = LifecycleEventObserver { _, event ->
      // Typing is saved as the app goes: the page's own save session runs before the screen is
      // torn down for a rotation or the app is backgrounded.
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) page.flushSoon()
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      if (state.page === page) state.page = null
      page.close()
    }
  }
  // A link tapped while reading: Android's own app for its kind. The caret put for writing by
  // the page itself: the keyboard up for it.
  DisposableEffect(state, page) {
    state.onOpenLink = { href -> openOutside(context, href) }
    state.onKeyboard = { page.focusForTyping() }
    onDispose {
      state.onOpenLink = {}
      state.onKeyboard = {}
    }
  }
  LaunchedEffect(page, ready, guid, folderGuid, editable, locked, found, theme) {
    if (ready) {
      state.open(guid, folderGuid, editable, locked, found, theme)
      // A new note opens ready to type, as New Note did before the page drew the note: the
      // editor takes the caret (the page does that itself) and the phone's keyboard comes up.
      // An existing note opens to be read: the keyboard stays down until it is tapped.
      if (guid == null) page.focusForTyping()
    }
  }
  // The keyboard takes the bottom, and the page shrinks above it: the caret stays in view.
  AndroidView(modifier = modifier.imePadding(), factory = { page.view })
}

/**
 * The WebView and what it may reach: the bundle's assets and the note media the engine holds,
 * from its own origin, and nothing else. The bridge is the page's only way to the engine, and
 * it exists only while the bundled page is what is shown.
 */
private class NotePageView(
  private val context: Context,
  engine: AccountInterface,
  private val state: NoteEditorState,
  private val onLink: (String) -> Unit,
) : NotePage {
  private val bridge = Bridge()
  private val closed = AtomicBoolean(false)
  private val host = NoteHost(engine) { clipboard(context) }
  private val media = NoteMedia(engine, context.cacheDir)

  /** Engine calls outlive this view: a save posted as the page goes must still land. */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val loader = WebViewAssetLoader.Builder()
    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
    .addPathHandler("/note-media/", media)
    .build()

  val view: WebView = WebView(context).apply {
    // The page's height is the view's: a WebView left at wrap-content (an AndroidView's default)
    // lays the page out on a viewport of no height, and the note shows only its first lines.
    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    settings.javaScriptEnabled = true
    // The page reads localStorage for its language and one setting; nothing else needs storage.
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.mediaPlaybackRequiresUserGesture = true
    settings.javaScriptCanOpenWindowsAutomatically = false
    // The page's text follows the person's font size, as the app's own text does.
    settings.textZoom = (resources.configuration.fontScale * 100).toInt()
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    webViewClient = Client()
    addJavascriptInterface(bridge, BRIDGE)
    loadUrl(PAGE)
  }

  /** Saves what is typed now, as the app goes to the background or the screen is rebuilt. */
  fun flushSoon() {
    scope.launch { state.flush() }
  }

  /**
   * A new note opens ready to type, and the page asks for the keyboard when it puts the caret
   * for writing itself (`host_keyboard`): the WebView takes Android's focus and asks for the
   * keyboard. Asked again a moment later, as the page draws its editor only after the note is
   * read, and focuses it only once a file going in has landed.
   */
  fun focusForTyping() {
    view.requestFocus()
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return
    fun show() {
      if (!closed.get()) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    view.post { show() }
    view.postDelayed({ show() }, 300)
  }

  /** The page is gone: its bridge and its view with it. */
  fun close() {
    if (!closed.compareAndSet(false, true)) return
    view.removeJavascriptInterface(BRIDGE)
    view.stopLoading()
    view.webViewClient = WebViewClient()
    (view.parent as? android.view.ViewGroup)?.removeView(view)
    view.destroy()
  }

  override fun open(args: JSONObject): Boolean = post("window.skykeepNotes && window.skykeepNotes.open($args)")

  override fun command(name: String, arg: Any?): Boolean =
    post("window.skykeepNotes && window.skykeepNotes.command(${JSONObject.quote(name)}, ${jsValue(arg)})")

  override fun flush(token: Long): Boolean = post("window.skykeepNotes && window.skykeepNotes.flush($token)")

  override fun refresh(): Boolean = post("window.skykeepNotes && window.skykeepNotes.refresh()")

  override fun attach(path: String): Boolean =
    post("window.skykeepNotes && window.skykeepNotes.attach(${JSONObject.quote(path)})")

  /** Runs one piece of script on the page; false when the page is gone. */
  private fun post(script: String): Boolean {
    if (closed.get()) return false
    return view.post {
      if (!closed.get()) runCatching { view.evaluateJavascript(script, null) }
    }
  }

  private fun jsValue(value: Any?): String = when (value) {
    null -> "null"
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
  }

  private fun ours(url: Uri): Boolean = url.scheme == "https" && url.host == "appassets.androidplatform.net"

  private inner class Client : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
      val url = request.url
      if (!ours(url)) return refused()
      return loader.shouldInterceptRequest(url) ?: missing()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
      val url = request.url
      if (ours(url)) return false
      // Never the WebView's own navigation: the person's own app takes the link.
      onLink(url.toString())
      return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
      // Only the bundled page may reach the engine.
      if (!url.startsWith(ORIGIN)) view.removeJavascriptInterface(BRIDGE)
    }

    private fun refused(): WebResourceResponse =
      WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    private fun missing(): WebResourceResponse =
      WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
  }

  /** What the page posts (BRIDGE.md): a command answered once, or an event, told as it is. */
  private inner class Bridge {
    @JavascriptInterface
    fun post(json: String) {
      if (closed.get()) return
      when (val message = host.read(json)) {
        is NoteMessage.Command -> scope.launch {
          val answer = host.answer(message)
          this@NotePageView.post(
            "window.skykeepNotes && window.skykeepNotes.resolve(" +
              "${answer.id}, ${answer.ok}, ${JSONObject.quote(answer.payload)})",
          )
        }
        is NoteMessage.Event -> view.post {
          if (!closed.get()) state.event(message.cmd, message.args)
        }
        // Not the envelope: there is no id to answer, and the page never asked.
        is NoteMessage.Unreadable -> Unit
      }
    }
  }
}

/** A copy's text, for the page's Add Link sheet: Android's clipboard, nothing else. */
private fun clipboard(context: Context): String? = runCatching {
  val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
  val clip = clipboard.primaryClip ?: return null
  (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).coerceToText(context)?.toString() }
}.getOrNull()

/** Hands a link to the app this phone uses for it; a scheme nobody takes is left alone. */
private fun openOutside(context: Context, href: String) {
  val uri = runCatching { Uri.parse(href) }.getOrNull() ?: return
  if (uri.scheme == null || uri.scheme !in OPEN_SCHEMES) return
  runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}
