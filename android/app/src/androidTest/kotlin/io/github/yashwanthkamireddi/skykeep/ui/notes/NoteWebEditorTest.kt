package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yashwanthkamireddi.skykeep.ui.theme.SkykeepTheme
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.quince_mobile.AccountInterface

/**
 * The note page inside the app: a fake `AccountInterface` holding one formatted note, the real
 * WebView, the real dispatcher and the real save path's arguments. Nothing here signs in, and no
 * note of the person's is read or written — the fake answers every call the page makes.
 *
 * Typing is done through the WebView itself (the editor focused, `insertText` as the browser
 * gives it), without the keyboard's composition: the owner checks a real keyboard on his phone.
 */
@RunWith(AndroidJUnit4::class)
class NoteWebEditorTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  private lateinit var state: NoteEditorState
  private lateinit var fake: FakeEngine

  /** One save the page asked for, as the page sent it. */
  private data class Save(val guid: String, val body: String, val base: String?, val runs: String?, val baseRuns: String?)

  /** The engine with one note: what the page reads and what it saves, and nothing else. */
  private class FakeEngine(note: String, runs: String) {
    @Volatile var body: String = note
    @Volatile var runs: String = runs
    /** The attachments opened by a tap, as the host was told. */
    val opened = CopyOnWriteArrayList<String>()
    val saves = CopyOnWriteArrayList<Save>()
    val unlocks = CopyOnWriteArrayList<String?>()
    var picture: ByteArray = ByteArray(0)
    /** What an attachment is, as the page is told before it draws it. */
    @Volatile var attachmentInfo: String = """{"id":"A-1","uti":"public.jpeg","kind":"image","filename":"IMG.jpg"}"""
    val engine: AccountInterface

    init {
      engine = Proxy.newProxyInstance(
        AccountInterface::class.java.classLoader,
        arrayOf(AccountInterface::class.java),
      ) { proxy, method, args ->
        // An unsigned argument gives the JVM method a hash after its name: the export's name
        // is the part before it.
        val name = method.name.substringBefore('-')
        val list = args?.toList() ?: emptyList()
        when (name) {
          "equals" -> proxy === list[0]
          "hashCode" -> System.identityHashCode(proxy)
          "toString" -> "FakeEngine"
          "noteBody" -> body
          "noteRunsJson" -> this.runs
          "noteSaveFormatted" -> {
            saves += Save(list[0] as String, list[1] as String, list[2] as String?, list[3] as String?, list[4] as String?)
            body = list[1] as String
            (list[3] as String?)?.let { this.runs = it }
            Unit
          }
          "noteAttachFileJson" -> attached(list[2].toString().toInt(), list[3] as String?)
          "noteCreate" -> {
            body = list[1] as String
            "NEW-1"
          }
          "noteUnlockJson" -> {
            unlocks += list[1] as String?
            throw IllegalStateException("Enter this note's password to open it.")
          }
          "noteLockHint" -> null
          "noteLockForget" -> Unit
          "noteTableJson" -> null
          "noteAttachmentInfoJson" -> attachmentInfo.replace("\"A-1\"", JSONObject.quote(list[0] as String))
          "noteMedia" -> picture
          "close" -> Unit
          else -> fallback(method.returnType)
        }
      } as AccountInterface
    }

    /**
     * A file added as the engine adds one (ckdocument insert_attachment_placed): on a line of
     * its own, a newline before it inside a line and after it unless it is the last character;
     * its run of its own, every other run kept.
     */
    private fun attached(atOffset: Int, base: String?): String {
      val text = base ?: body
      var at = atOffset.coerceIn(0, text.length)
      val inside = { p: Int -> p > 0 && text[p - 1] != '\n' }
      if (inside(at) && text.getOrNull(at) == '\n') at += 1
      val before = if (inside(at)) "\n" else ""
      val after = if (at < text.length) "\n" else ""
      val next = text.substring(0, at) + before + "\ufffc" + after + text.substring(at)
      val offset = at + before.length
      val laid = JSONArray()
      var pos = 0
      val old = JSONArray(runs)
      for (i in 0 until old.length()) {
        val r = old.getJSONObject(i)
        val len = r.getInt("length")
        val end = pos + len
        if (at in pos until end || (at == end && i == old.length() - 1)) {
          val head = at - pos
          if (head + before.length > 0) laid.put(JSONObject(r.toString()).put("length", head + before.length))
          laid.put(JSONObject().put("length", 1).put("attachment", "ATT-1"))
          val tail = len - head + after.length
          if (tail > 0) laid.put(JSONObject(r.toString()).put("length", tail))
        } else {
          laid.put(r)
        }
        pos = end
      }
      body = next
      runs = laid.toString()
      return JSONObject()
        .put("attachment", "ATT-1").put("media", "ATT-1").put("uti", "public.jpeg")
        .put("offset", offset).put("body", next).put("runs", laid)
        .toString()
    }

    private fun fallback(type: Class<*>): Any? = when {
      type == Void.TYPE -> Unit
      type == java.lang.Boolean.TYPE -> false
      type == java.lang.Integer.TYPE -> 0
      type == java.lang.Long.TYPE -> 0L
      type == java.lang.Double.TYPE -> 0.0
      type == java.lang.Float.TYPE -> 0f
      type == java.lang.Short.TYPE -> 0.toShort()
      type == java.lang.Byte.TYPE -> 0.toByte()
      type == java.lang.Character.TYPE -> '\u0000'
      else -> null
    }
  }

  private fun show(guid: String?, locked: Boolean = false, editable: Boolean = true, dark: Boolean = false) {
    state = NoteEditorState()
    compose.setContent {
      Themed(dark) {
        // As NoteScreen leaves the note: what is typed is saved, then the note is gone.
        var left by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        if (left) {
          Text("left")
        } else {
          BackHandler { scope.launch { state.flush(); left = true } }
          state.onOpenAttachment = { id, _, _ -> fake.opened += id }
          NoteWebEditor(fake.engine, state, guid, null, editable, locked, emptyList(), Modifier.fillMaxSize())
        }
      }
    }
    compose.waitUntil(30_000) { state.ready.value }
  }

  /** The app's theme, light or dark as asked, whatever the emulator is set to. */
  @Composable
  private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    val base = LocalConfiguration.current
    val config = Configuration(base).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    CompositionLocalProvider(LocalConfiguration provides config) {
      SkykeepTheme(dark = dark) { content() }
    }
  }

  private fun web(): WebView {
    compose.waitUntil(10_000) { findWeb(compose.activity.window.decorView) != null }
    return findWeb(compose.activity.window.decorView)!!
  }

  private fun findWeb(view: View): WebView? = when (view) {
    is WebView -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
    else -> null
  }

  /** Runs one piece of script on the page and answers its value, JSON as the bridge gives it. */
  private fun js(script: String): String {
    val latch = CountDownLatch(1)
    val out = AtomicReference("")
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      web().evaluateJavascript(script) { value ->
        out.set(value)
        latch.countDown()
      }
    }
    assertTrue("the page never answered: $script", latch.await(15, TimeUnit.SECONDS))
    return out.get()
  }

  /** Waits for what the page shows to be what the note holds: `check` reads its JSON answer. */
  private fun waitFor(script: String, timeoutMs: Long = 20_000, check: (String) -> Boolean): String {
    val end = System.currentTimeMillis() + timeoutMs
    var last = ""
    while (System.currentTimeMillis() < end) {
      // Compose draws on the test's clock: let it run the host's side (opening the note) first.
      compose.waitForIdle()
      last = js(script)
      if (check(last)) return last
      Thread.sleep(120)
    }
    val body = js("document.body ? document.body.innerHTML.slice(0, 800) : 'no body'")
    val api = js("String(typeof window.skykeepNotes)")
    throw AssertionError("timed out waiting for $script (last answer: $last; api: $api; page: $body)")
  }

  private fun text(): String = "document.querySelector('.ProseMirror')?.innerText ?? ''"

  /** What the page shows of the one note. */
  private fun opened(note: String) {
    fake = FakeEngine(note, """[{"length":${note.length}}]""")
    show("N-1")
    waitFor(text()) { it.contains("Lemon") }
  }

  @Test
  fun the_page_reports_ready_and_shows_the_note_s_text() {
    fake = FakeEngine("Lemon\ntart", """[{"length":6,"bold":true},{"length":4}]""")
    show("N-1")

    assertTrue(state.ready.value)
    val shown = waitFor(text()) { it.contains("Lemon") }
    assertTrue(shown, shown.contains("Lemon"))
    // The runs arrived and the editor draws them: the bold stretch is bold.
    assertEquals("true", js("document.querySelector('.ProseMirror strong') !== null"))
    // Kept for the report: the note open in the page. The window's own pixels first, and the
    // WebView drawn into a bitmap as well (a browser's surface is not in every capture).
    Thread.sleep(1_000)
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
    val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    if (shot != null && dir != null) {
      File(dir, "note-web-editor.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    val web = web()
    val drawn = runCatching {
      val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
      InstrumentationRegistry.getInstrumentation().runOnMainSync { web.draw(android.graphics.Canvas(bitmap)) }
      bitmap
    }.getOrNull()
    if (drawn != null && dir != null) {
      File(dir, "note-web-editor-view.png").outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
  }

  @Test
  fun bold_then_typing_saves_runs_that_carry_bold() {
    opened("Lemon\n")

    state.command("bold")
    Thread.sleep(300)
    js("document.querySelector('.ProseMirror')?.focus()")
    js("document.execCommand('insertText', false, 'Bold')")
    waitFor(text()) { it.contains("Bold") }

    runBlocking { state.flush() }

    val save = fake.saves.last()
    assertEquals("N-1", save.guid)
    assertTrue(save.body, save.body.contains("Bold"))
    assertNotNull(save.runs)
    assertTrue(save.runs, save.runs!!.contains("\"bold\":true"))
  }

  @Test
  fun back_flushes_typing_not_yet_saved() {
    opened("Lemon\n")

    js("document.querySelector('.ProseMirror')?.focus()")
    js("document.execCommand('insertText', false, 'unsaved')")
    waitFor(text()) { it.contains("unsaved") }

    compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    compose.waitUntil(20_000) { fake.saves.isNotEmpty() }
    assertTrue(fake.saves.last().body, fake.saves.last().body.contains("unsaved"))
  }

  @Test
  fun an_outside_address_is_refused() {
    opened("Lemon\n")

    js("location.href = 'https://example.com/'")
    Thread.sleep(1_000)

    assertTrue(
      js("location.href"),
      js("location.href").contains("appassets.androidplatform.net"),
    )
  }

  @Test
  fun a_locked_note_shows_the_password_form() {
    fake = FakeEngine("Secret\n", "[]")
    show("N-1", locked = true)

    waitFor("document.querySelector('input[type=\"password\"]') !== null") { it == "true" }
    // The page asked with no password first (none remembered), and was refused.
    assertTrue(fake.unlocks.isNotEmpty())
    assertNull(fake.unlocks.first())
  }

  @Test
  fun a_heic_picture_is_served_as_jpeg() {
    val heic = InstrumentationRegistry.getInstrumentation().context.assets.open("two.heic").readBytes()
    fake = FakeEngine("", "[]").apply { picture = heic }
    val dir = File(compose.activity.cacheDir, "note-media-test").apply {
      deleteRecursively()
      mkdirs()
    }
    val media = NoteMedia(fake.engine, dir)

    val response = media.handle("/A-1/file")
    assertNotNull(response)
    assertEquals("image/jpeg", response!!.mimeType)
    val shown = response.data.readBytes()
    assertEquals(0xFF, shown[0].toInt() and 0xFF)
    assertEquals(0xD8, shown[1].toInt() and 0xFF)
    assertNotNull(BitmapFactory.decodeByteArray(shown, 0, shown.size))
    // Served from what was made the next time, with nothing asked of the engine.
    val again = media.handle("/A-1/file")
    assertEquals("image/jpeg", again!!.mimeType)
  }

  /** A drawing Apple renders as ink on white: what the engine hands over for both ink parts. */
  private fun drawingPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val ink = Paint().apply {
      color = android.graphics.Color.rgb(21, 26, 34)
      strokeWidth = 8f
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
    }
    canvas.drawRect(120f, 120f, 680f, 280f, ink)
    canvas.drawCircle(260f, 80f, 26f, ink)
    return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  /**
   * A sketch made on an iPhone is drawn where it sits, from the rendering Apple keeps for it:
   * both ink parts the page asks for, the one its look shows drawn, and on a white card in the
   * dark look, where the ink would otherwise be lost.
   */
  @Test
  fun a_drawing_made_on_a_phone_is_drawn() {
    fake = FakeEngine("Sketch\n\ufffc\n", """[{"length":7},{"length":1,"attachment":"DRAW-1"},{"length":1}]""")
    fake.picture = drawingPng()
    fake.attachmentInfo =
      """{"id":"DRAW-1","uti":"com.apple.paper","kind":"drawing","title":"Island","width":800,"height":400,"size":9000,"hasPreview":true}"""
    show("N-1", dark = true)
    waitFor("document.querySelectorAll('img.nf-drawing').length") { it == "2" }
    // The dark look's ink is the one shown, drawn, and on a white card.
    val drawn = waitFor(
      "(() => { const img = document.querySelector('img.nf-ink-dark');" +
        "return img && img.complete && img.naturalWidth > 0 && getComputedStyle(img).display !== 'none'" +
        "? img.naturalWidth + ':' + getComputedStyle(img).backgroundColor : 'no'; })()",
    ) { it.contains("800:") }
    assertTrue(drawn, drawn.contains("800:rgb(255, 255, 255)"))
    // Never the file's card in its place.
    assertEquals("0", js("document.querySelectorAll('.nf-attachment.is-file').length"))
  }

  /** A drawing stored as a HEIC is made drawable for both of the page's ink parts. */
  @Test
  fun a_heic_drawing_is_served_as_jpeg_for_both_ink_parts() {
    val heic = InstrumentationRegistry.getInstrumentation().context.assets.open("two.heic").readBytes()
    fake = FakeEngine("", "[]").apply { picture = heic }
    val dir = File(compose.activity.cacheDir, "note-media-drawing").apply {
      deleteRecursively()
      mkdirs()
    }
    val media = NoteMedia(fake.engine, dir)

    for (part in listOf("drawing", "drawing-dark")) {
      val response = media.handle("/DRAW-1/$part")
      assertNotNull("no bytes for $part", response)
      assertEquals("image/jpeg", response!!.mimeType)
      val shown = response.data.readBytes()
      assertNotNull(BitmapFactory.decodeByteArray(shown, 0, shown.size))
    }
  }

  /** A new note opens ready to type; an existing one opens to be read, the keyboard down. */
  @Test(timeout = 120_000)
  fun a_new_note_opens_focused_with_the_keyboard_up() {
    fake = FakeEngine("", "[]")
    show(guid = null)

    assertEquals("true", waitFor("document.activeElement?.classList.contains('ProseMirror') === true") { it == "true" })
    var webFocused = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync { webFocused = web().hasFocus() }
    assertTrue("the WebView has no focus", webFocused)
    assertTrue("the keyboard is down on a new note", waitForKeyboard(up = true))
  }

  @Test(timeout = 120_000)
  fun an_existing_note_opens_to_be_read_with_the_keyboard_down() {
    fake = FakeEngine("Lemon\ntart", """[{"length":10}]""")
    show("N-1")
    waitFor(text()) { it.contains("Lemon") }
    Thread.sleep(1_000)
    assertTrue("the keyboard came up on a note to read", !keyboardShown())
  }

  // ---- writing around a picture: real touches, as a finger gives them ----------------------

  /** A shell command run as the test's own (`input tap`, `input text`), waited for. */
  private fun shell(command: String) {
    val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
  }

  /** Where the page's CSS point (x, y) is on the screen, in pixels. */
  private fun onScreen(x: Double, y: Double): Pair<Int, Int> {
    val scale = js("window.devicePixelRatio").toDouble()
    val at = IntArray(2)
    InstrumentationRegistry.getInstrumentation().runOnMainSync { web().getLocationOnScreen(at) }
    return Pair((at[0] + x * scale).toInt(), (at[1] + y * scale).toInt())
  }

  /** The box of the first element `selector` finds, in CSS pixels: left, top, right, bottom. */
  private fun box(selector: String): DoubleArray {
    val json = js("(() => { const r = document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom].join(','); })()")
    return json.trim('"').split(',').map { it.toDouble() }.toDoubleArray()
  }

  private fun tap(x: Double, y: Double) {
    val (sx, sy) = onScreen(x, y)
    shell("input tap $sx $sy")
  }

  /** A finger held still for most of a second: Android's long press. */
  private fun longPress(x: Double, y: Double) {
    val (sx, sy) = onScreen(x, y)
    shell("input swipe $sx $sy $sx $sy 900")
  }

  private fun lineCount(): String = "document.querySelectorAll('.ProseMirror > p').length"

  /** A photo the engine hands over for a picture: 1600 by 1000, as a phone's is shaped. */
  private fun photoJpeg(): ByteArray {
    val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(31, 111, 139))
    val tile = Paint().apply { color = android.graphics.Color.rgb(230, 213, 184) }
    for (x in 0 until 8) for (y in 0 until 5) if ((x + y) % 2 == 0) canvas.drawRect(x * 200f + 8, y * 200f + 8, x * 200f + 192, y * 200f + 192, tile)
    return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
  }

  private val photoInfo =
    """{"id":"A-1","uti":"public.jpeg","kind":"image","filename":"IMG_0412.jpg","width":1600,"height":1000,"size":9000,"hasPreview":false}"""

  /** A note whose last thing is a picture, on a line of its own, opened and drawn. */
  private fun pictureLast(dark: Boolean = false): String {
    val note = "Bathroom\nThe tiles we chose:\n￼"
    fake = FakeEngine(note, """[{"length":9},{"length":20},{"length":1,"attachment":"A-1"}]""")
    fake.picture = photoJpeg()
    fake.attachmentInfo = photoInfo
    show("N-1", dark = dark)
    waitFor("(() => { const i = document.querySelector('img.nf-image'); return Boolean(i && i.complete && i.naturalWidth > 0); })()") { it == "true" }
    return note
  }

  /** Where the attachment runs of a save sit in its text, with their ids. */
  private fun attachmentsIn(save: Save): List<Pair<Int, String>> {
    val runs = JSONArray(save.runs!!)
    val out = mutableListOf<Pair<Int, String>>()
    var at = 0
    for (i in 0 until runs.length()) {
      val r = runs.getJSONObject(i)
      if (r.has("attachment") && !r.isNull("attachment")) out += Pair(at, r.getString("attachment"))
      at += r.getInt("length")
    }
    assertEquals("the runs do not cover the text", save.body.length, at)
    return out
  }

  /** The screen as it is now, kept for the report. */
  private fun shot(name: String) {
    Thread.sleep(600)
    val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
    File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  /** 1. A tap under a picture that ends the note gives a line to type on, the keyboard up; only
   *  what is typed on it is saved, the picture's run where it was. */
  @Test(timeout = 180_000)
  fun a_tap_under_a_picture_that_ends_the_note_gives_a_line_to_type_on() {
    val note = pictureLast()
    assertEquals("3", js(lineCount()))
    assertTrue("the keyboard was up before the tap", waitForKeyboard(up = false))
    val picture = box(".ProseMirror > p:last-child")
    tap((picture[0] + picture[2]) / 2, picture[3] + 28)

    waitFor(lineCount()) { it == "4" }
    assertEquals("\"\"", js("document.querySelector('.ProseMirror > p:last-child').textContent"))
    assertEquals("true", js("document.activeElement?.classList.contains('ProseMirror') === true"))
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    assertTrue("the picture opened", fake.opened.isEmpty())
    shot("around-1-line-under-picture.png")
    runBlocking { state.flush() }
    assertTrue("saved for a line only looked at: ${fake.saves}", fake.saves.isEmpty())

    shell("input text Grout")
    waitFor(text()) { it.contains("Grout") }
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("$note\nGrout", save.body)
    assertEquals(listOf(Pair(note.length - 1, "A-1")), attachmentsIn(save))
  }

  /** 2. A file added with More > Add File: the caret is on a new line under it, the keyboard
   *  up, and what is typed there is saved after it. */
  @Test(timeout = 180_000)
  fun after_a_file_is_added_the_caret_is_on_a_new_line_under_it() {
    fake = FakeEngine("Packing\nTowels", """[{"length":14}]""")
    fake.picture = photoJpeg()
    fake.attachmentInfo = photoInfo
    show("N-1")
    waitFor(text()) { it.contains("Towels") }
    assertTrue("the keyboard was up before the file", waitForKeyboard(up = false))

    // As More > Add File hands a picked picture over (attachPicked).
    state.attach(File(compose.activity.cacheDir, "IMG_0412.jpg").path)

    waitFor(lineCount()) { it == "4" }
    waitFor("document.activeElement?.classList.contains('ProseMirror') === true") { it == "true" }
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    shot("around-2-after-attach.png")
    val added = fake.body
    assertEquals("Packing\nTowels\n￼", added)
    runBlocking { state.flush() }
    assertTrue("saved before anything was typed: ${fake.saves}", fake.saves.isEmpty())

    shell("input text Sunscreen")
    waitFor(text()) { it.contains("Sunscreen") }
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("$added\nSunscreen", save.body)
    assertEquals(listOf(Pair(added.length - 1, "ATT-1")), attachmentsIn(save))
  }

  /** 4. A single tap opens the picture; a long press chooses it instead, ringed as chosen, and
   *  the keyboard's delete then takes it out of the note. */
  @Test(timeout = 180_000)
  fun a_long_press_chooses_a_picture_and_delete_removes_it() {
    pictureLast()
    val picture = box("img.nf-image")
    val x = (picture[0] + picture[2]) / 2
    val y = (picture[1] + picture[3]) / 2

    tap(x, y)
    compose.waitUntil(10_000) { fake.opened.isNotEmpty() }
    assertEquals(listOf("A-1"), fake.opened.toList())

    longPress(x, y)
    waitFor("document.querySelector('.nf-attachment[data-chosen]') !== null") { it == "true" }
    Thread.sleep(800)
    assertEquals("the long press opened it", 1, fake.opened.size)
    assertEquals("\"rgb(122, 86, 0)\"", js("getComputedStyle(document.querySelector('[data-chosen] img')).outlineColor"))
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    shot("around-4-chosen-light.png")

    watchKeys()
    shell("input keyevent KEYCODE_DEL")
    val gone = runCatching { waitFor("document.querySelector('img.nf-image') === null", timeoutMs = 5_000) { it == "true" } }
    assertTrue("the picture is still there; the page saw ${keysSeen()}", gone.isSuccess)
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("Bathroom\nThe tiles we chose:\n", save.body)
    assertEquals(emptyList<Pair<Int, String>>(), attachmentsIn(save))
  }

  /** What the page saw of the keys, for a test's failure: each event, and where the caret was. */
  private fun watchKeys() = js(
    "window.__keys = []; ['keydown','beforeinput','input','selectionchange'].forEach(t => document.addEventListener(t, e => " +
      "window.__keys.push(t + ':' + (e.key || e.inputType || '') + ':' + document.getSelection().anchorNode?.nodeName + '@' + document.getSelection().anchorOffset), true))",
  )

  private fun keysSeen(): String = js("JSON.stringify(window.__keys)")

  /** 3. The arrow keys go from the line above to just before a picture and then just after
   *  it; the keyboard's delete there takes it out. */
  @Test(timeout = 180_000)
  fun the_arrow_keys_go_past_a_picture_and_delete_after_it_removes_it() {
    val note = "Tiles:\n\ufffc\nGrout"
    fake = FakeEngine(note, """[{"length":7},{"length":1,"attachment":"A-1"},{"length":6}]""")
    fake.picture = photoJpeg()
    fake.attachmentInfo = photoInfo
    show("N-1")
    waitFor("(() => { const i = document.querySelector('img.nf-image'); return Boolean(i && i.complete && i.naturalWidth > 0); })()") { it == "true" }
    // The caret at the end of "Tiles:", tapped there.
    val words = box(".ProseMirror > p:first-child")
    tap(words[2] - 4, (words[1] + words[3]) / 2)
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    watchKeys()
    shell("input keyevent KEYCODE_DPAD_RIGHT")
    shell("input keyevent KEYCODE_DPAD_RIGHT")
    Thread.sleep(500)
    shell("input keyevent KEYCODE_DEL")
    val seen = runCatching { waitFor("document.querySelector('img.nf-image') === null", timeoutMs = 5_000) { it == "true" } }
    assertTrue("the picture is still there; the page saw ${keysSeen()}", seen.isSuccess)
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("Tiles:\n\nGrout", save.body)
    assertEquals(emptyList<Pair<Int, String>>(), attachmentsIn(save))
  }

  /** 4, in the dark look: the chosen picture is ringed in the dark accent, as clearly. */
  @Test(timeout = 180_000)
  fun a_chosen_picture_is_ringed_in_the_dark_look_too() {
    pictureLast(dark = true)
    val picture = box("img.nf-image")
    longPress((picture[0] + picture[2]) / 2, (picture[1] + picture[3]) / 2)
    waitFor("document.querySelector('.nf-attachment[data-chosen]') !== null") { it == "true" }
    assertEquals("\"rgb(242, 179, 61)\"", js("getComputedStyle(document.querySelector('[data-chosen] img')).outlineColor"))
    assertTrue("the long press opened it", fake.opened.isEmpty())
    shot("around-4-chosen-dark.png")
  }

  // ---- F4: a picture taller than the screen at the end of the note ------------------------

  /** A phone's long screenshot, as the engine hands one over: 1080 by 3000, drawn the note's
   *  width wide it is taller than the screen (a 1080 by 2400 one is, under the app's bars). */
  private fun screenshotPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(1080, 3000, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.rgb(31, 111, 139))
    val band = Paint().apply { color = android.graphics.Color.rgb(230, 213, 184) }
    for (y in 0 until 15) if (y % 2 == 0) canvas.drawRect(0f, y * 200f, 1080f, y * 200f + 200f, band)
    return java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }

  private val screenshotInfo =
    """{"id":"A-1","uti":"public.png","kind":"image","filename":"Screenshot.png","width":1080,"height":3000,"size":90000,"hasPreview":false}"""

  private fun pictureShown(): String =
    "(() => { const i = document.querySelector('img.nf-image'); return Boolean(i && i.complete && i.naturalWidth > 0); })()"

  private fun atEnd(): String =
    "(() => { const p = document.querySelector('.note-editor'); return p.scrollTop + p.clientHeight >= p.scrollHeight - 1; })()"

  /** The note's pane scrolled to its very end by a finger, as a person does: swipes up until
   *  the pane goes no further. */
  private fun swipeToEnd() {
    val pane = box(".note-editor")
    val x = (pane[0] + pane[2]) / 2
    val (sx, from) = onScreen(x, pane[1] + (pane[3] - pane[1]) * 0.8)
    val (_, to) = onScreen(x, pane[1] + (pane[3] - pane[1]) * 0.2)
    repeat(12) {
      if (js(atEnd()) == "true") return
      shell("input swipe $sx $from $sx $to 250")
      Thread.sleep(500)
    }
    assertEquals("the pane did not reach its end", "true", js(atEnd()))
  }

  /** How far the pane's bottom edge sits above the keyboard (or the screen's foot), in pixels:
   *  0 or more when the whole pane is in view. */
  private fun paneClearOfKeyboard(): Int {
    val pane = box(".note-editor")
    val (_, bottom) = onScreen(0.0, pane[3])
    var keyboardTop = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val root = compose.activity.window.decorView
      val ime = androidx.core.view.ViewCompat.getRootWindowInsets(root)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
      keyboardTop = root.height - ime
    }
    return keyboardTop - bottom
  }

  /** F4, 1. However tall the last thing is, scrolled to the end there is a clear room under it
   *  (keyboard down and up), and a tap there gives the line to type on, not the picture. */
  @Test(timeout = 240_000)
  fun a_picture_taller_than_the_screen_still_leaves_room_under_it_to_write() {
    val note = "Screenshot\n￼"
    fake = FakeEngine(note, """[{"length":11},{"length":1,"attachment":"A-1"}]""")
    fake.picture = screenshotPng()
    fake.attachmentInfo = screenshotInfo
    show("N-1")
    waitFor(pictureShown()) { it == "true" }
    assertTrue("the keyboard was up before the tap", waitForKeyboard(up = false))
    val picture0 = box("img.nf-image")
    val pane0 = box(".note-editor")
    assertTrue("the picture is not taller than the pane: ${picture0.toList()} in ${pane0.toList()}", picture0[3] - picture0[1] > pane0[3] - pane0[1])

    swipeToEnd()
    val picture = box("img.nf-image")
    val pane = box(".note-editor")
    val room = pane[3] - picture[3]
    assertTrue("only ${room}px under the picture, keyboard down", room >= 144)
    assertEquals("the room does not say it is there to write in", "\"block\"", js("getComputedStyle(document.querySelector('.m-room-hint')).display"))
    shot("f4-tall-picture-end-keyboard-down.png")

    tap((picture[0] + picture[2]) / 2, picture[3] + room / 2)
    waitFor(lineCount()) { it == "3" }
    assertEquals("\"\"", js("document.querySelector('.ProseMirror > p:last-child').textContent"))
    assertEquals("true", js("document.activeElement?.classList.contains('ProseMirror') === true"))
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    assertTrue("the picture opened: ${fake.opened}", fake.opened.isEmpty())
    assertEquals("\"none\"", js("getComputedStyle(document.querySelector('.m-room-hint')).display"))

    // With the keyboard up, the end of the note still shows room under the line written on.
    Thread.sleep(800)
    swipeToEnd()
    val line = box(".ProseMirror > p:last-child")
    val paneUp = box(".note-editor")
    val metrics = js("(() => { const p = document.querySelector('.note-editor'); const r = document.querySelector('.m-room').getBoundingClientRect(); return [p.scrollTop, p.clientHeight, p.scrollHeight, r.top, r.bottom, innerHeight].join(','); })()")
    assertTrue("the line under the picture is not in view: ${line.toList()} in ${paneUp.toList()} ($metrics)", line[1] >= paneUp[1] && line[3] <= paneUp[3])
    assertTrue("only ${paneUp[3] - line[3]}px under the line, keyboard up", paneUp[3] - line[3] >= 144)
    assertTrue("the pane runs under the keyboard by ${-paneClearOfKeyboard()}px", paneClearOfKeyboard() >= -2)
    shot("f4-tall-picture-end-keyboard-up.png")

    runBlocking { state.flush() }
    assertTrue("saved for a line only looked at: ${fake.saves}", fake.saves.isEmpty())
    shell("input text Below")
    waitFor(text()) { it.contains("Below") }
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("$note\nBelow", save.body)
    assertEquals(listOf(Pair(note.length - 1, "A-1")), attachmentsIn(save))
  }

  /** F4, 2. A picture taller than the screen just added: the line under it is in view above the
   *  keyboard, so the person sees the caret, not the middle of the picture. */
  @Test(timeout = 240_000)
  fun after_a_tall_picture_is_added_the_line_under_it_is_in_view_above_the_keyboard() {
    fake = FakeEngine("Packing\nTowels", """[{"length":14}]""")
    fake.picture = screenshotPng()
    fake.attachmentInfo = screenshotInfo
    show("N-1")
    waitFor(text()) { it.contains("Towels") }
    assertTrue("the keyboard was up before the file", waitForKeyboard(up = false))

    // As More > Add File hands a picked picture over (attachPicked).
    state.attach(File(compose.activity.cacheDir, "Screenshot.png").path)

    waitFor(lineCount()) { it == "4" }
    waitFor(pictureShown()) { it == "true" }
    assertTrue("the keyboard did not come up", waitForKeyboard(up = true))
    Thread.sleep(1_500)
    val line = box(".ProseMirror > p:last-child")
    val pane = box(".note-editor")
    assertTrue("the line under the picture is out of view: ${line.toList()} in ${pane.toList()}", line[1] >= pane[1] && line[3] <= pane[3])
    assertTrue("the pane runs under the keyboard by ${-paneClearOfKeyboard()}px", paneClearOfKeyboard() >= -2)
    shot("f4-after-adding-a-tall-picture.png")

    val added = fake.body
    assertEquals("Packing\nTowels\n￼", added)
    runBlocking { state.flush() }
    assertTrue("saved before anything was typed: ${fake.saves}", fake.saves.isEmpty())
    shell("input text Sunscreen")
    waitFor(text()) { it.contains("Sunscreen") }
    runBlocking { state.flush() }
    val save = fake.saves.last()
    assertEquals("$added\nSunscreen", save.body)
    assertEquals(listOf(Pair(added.length - 1, "ATT-1")), attachmentsIn(save))
  }

  /** Whether the phone's keyboard is up over the page. */
  private fun keyboardShown(): Boolean {
    var shown = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val root = compose.activity.window.decorView
      shown = (androidx.core.view.ViewCompat.getRootWindowInsets(root)
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
    }
    return shown
  }

  private fun waitForKeyboard(up: Boolean, timeoutMs: Long = 15_000): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (keyboardShown() == up) return true
      Thread.sleep(150)
    }
    return false
  }
}

