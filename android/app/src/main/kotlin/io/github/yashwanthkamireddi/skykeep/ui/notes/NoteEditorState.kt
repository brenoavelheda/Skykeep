package io.github.yashwanthkamireddi.skykeep.ui.notes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** What the format bar lights: the page's report of the caret and of typing not yet with the engine. */
data class EditorStatus(
  /** Notes' paragraph style number (0 title, 1 heading, 2 subheading, 4 monospaced, 100 to 103 lists and checklist), or null for body. */
  val style: Int? = null,
  val bold: Boolean = false,
  val italic: Boolean = false,
  val underline: Boolean = false,
  val strike: Boolean = false,
  val checklist: Boolean = false,
  val quote: Boolean = false,
  /** Notes' text colour: 1 purple, 2 pink, 3 orange, 4 mint, 5 blue; null none. */
  val colour: Int? = null,
  val link: Boolean = false,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  /** Typing not yet with the engine. */
  val dirty: Boolean = false,
)

/** The page's own side of the bridge (BRIDGE.md, Methods): what the host says to it. */
internal interface NotePage {
  fun open(args: JSONObject): Boolean
  fun command(name: String, arg: Any?): Boolean
  fun flush(token: Long): Boolean
  fun refresh(): Boolean
  fun attach(path: String): Boolean
}

/**
 * The note page's state as the app holds it: what the page says about the caret and about
 * typing, the note a new one became, and the calls the top bar and the format bar make. The page
 * owns the save session; this asks it to flush and waits for the page to say it did.
 */
class NoteEditorState {
  internal var page: NotePage? = null

  private val _status = MutableStateFlow(EditorStatus())
  val status: StateFlow<EditorStatus> = _status.asStateFlow()

  private val _ready = MutableStateFlow(false)
  val ready: StateFlow<Boolean> = _ready.asStateFlow()

  private val _guid = MutableStateFlow<String?>(null)
  val guid: StateFlow<String?> = _guid.asStateFlow()

  /** A picture, drawing, document or file tapped: the host shows it (T5's viewer). */
  var onOpenAttachment: (id: String, kind: String, name: String) -> Unit = { _, _, _ -> }

  /** A link tapped, already checked by the page as one Skykeep opens. */
  internal var onOpenLink: (href: String) -> Unit = {}

  /** A save, create or added file landed: the library changed, so the list is read again. */
  internal var onSaved: (guid: String) -> Unit = {}

  /** Something the person should know, in the page's own words. */
  internal var onError: (message: String) -> Unit = {}

  /** The flushes asked for and not yet answered by the page, by token. */
  private val flushing = mutableMapOf<Long, CompletableDeferred<Unit>>()
  private var nextToken = 0L

  fun command(name: String, arg: Any? = null) {
    page?.command(name, arg)
  }

  fun attach(path: String) {
    page?.attach(path)
  }

  /** The library changed (a sync landed): the page reads the note again under its rules. */
  fun refresh() {
    page?.refresh()
  }

  /**
   * Asks the page to save what is typed now and returns when it says it has, or after two
   * seconds: leaving the note, backgrounding the app or a teardown must never wait on a page
   * that cannot answer.
   */
  suspend fun flush() {
    val page = page ?: return
    val token = synchronized(this) { ++nextToken }
    val done = CompletableDeferred<Unit>()
    synchronized(this) { flushing[token] = done }
    try {
      if (page.flush(token)) withTimeoutOrNull(2_000) { done.await() }
    } finally {
      synchronized(this) { flushing.remove(token) }
    }
  }

  /**
   * Shows a note in the page: `guid` null is a new one, made by the page with `note_create` once
   * something is typed. An existing note's guid is kept here for the top bar; a new note's
   * arrives on `host_saved`.
   */
  internal fun open(guid: String?, folderGuid: String?, editable: Boolean, locked: Boolean, found: List<String>, theme: String) {    if (guid != null) _guid.value = guid
    _status.value = EditorStatus()
    val args = JSONObject().apply {
      put("guid", guid ?: JSONObject.NULL)
      put("folderGuid", folderGuid ?: JSONObject.NULL)
      put("editable", editable)
      put("locked", locked)
      put("found", JSONArray(found))
      put("theme", theme)
    }
    page?.open(args)
  }

  /** The page was rebuilt (a rotation): ready is asked of it again. */
  internal fun resetReady() {
    _ready.value = false
  }

  /** One event the page posted (BRIDGE.md, Events): told, never answered. */
  internal fun event(cmd: String, args: JSONObject) {
    when (cmd) {
      "host_ready" -> _ready.value = true
      "host_status" -> _status.value = EditorStatus(
        style = args.number("style"),
        bold = args.optBoolean("bold", false),
        italic = args.optBoolean("italic", false),
        underline = args.optBoolean("underline", false),
        strike = args.optBoolean("strike", false),
        checklist = args.optBoolean("checklist", false),
        quote = args.optBoolean("quote", false),
        colour = args.number("colour"),
        link = args.optBoolean("link", false),
        canUndo = args.optBoolean("canUndo", false),
        canRedo = args.optBoolean("canRedo", false),
        dirty = args.optBoolean("dirty", false),
      )
      "host_saved" -> args.text("guid")?.let { guid ->
        _guid.value = guid
        onSaved(guid)
      }
      "host_flushed" -> {
        val token = args.number("token")?.toLong() ?: -1
        synchronized(this) { flushing.remove(token) }?.complete(Unit)
      }
      "host_error" -> args.text("message")?.let(onError)
      "host_open_link" -> args.text("href")?.let(onOpenLink)
      "host_open_attachment" -> onOpenAttachment(
        args.text("id").orEmpty(),
        args.text("kind") ?: "file",
        args.text("name").orEmpty(),
      )
      // host_flushed and anything a later version of the page adds: nothing to do here.
      else -> Unit
    }
  }

  private fun JSONObject.text(name: String): String? = when (val value: Any? = opt(name)) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
  }

  private fun JSONObject.number(name: String): Int? = when (val value: Any? = opt(name)) {
    null, JSONObject.NULL -> null
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
  }
}
