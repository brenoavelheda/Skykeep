package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yashwanthkamireddi.skykeep.ui.theme.AppTheme
import io.github.yashwanthkamireddi.skykeep.ui.theme.Palette
import io.github.yashwanthkamireddi.skykeep.ui.theme.SkykeepTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.quince_mobile.AccountInterface

/**
 * The note screen's chrome: the format bar, the styles sheet, export, attach and the picture
 * viewer, against a fake `AccountInterface` and, where the page is needed, the real WebView.
 * Nothing here signs in, and no note of the person's is read or written.
 *
 * Screenshots for the report are kept in the app's external files folder (`chrome-*.png`).
 */
@RunWith(AndroidJUnit4::class)
class FormatBarTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  /** What the host asked of the page, as the page's side of the bridge would get it. */
  private class RecordingPage : NotePage {
    val commands = CopyOnWriteArrayList<Pair<String, Any?>>()
    val attached = CopyOnWriteArrayList<String>()
    override fun open(args: JSONObject) = true
    override fun command(name: String, arg: Any?): Boolean = commands.add(name to arg)
    override fun flush(token: Long) = false
    override fun refresh() = true
    override fun attach(path: String): Boolean = attached.add(path)
  }

  /** The engine with one note, answering every call the page and the chrome make, and nothing else. */
  private class FakeEngine(note: String, runs: String) {
    @Volatile var body: String = note
    /** The note's runs as the engine keeps them: the last ones saved, read back as they are. */
    @Volatile var storedRuns: String = runs
    val markdownAsked = CopyOnWriteArrayList<Pair<String, String>>()
    /** Each save the page asked for: its text and its runs, as the page sent them. */
    val saves = CopyOnWriteArrayList<Pair<String, String?>>()
    var picture: ByteArray = ByteArray(0)
    val engine: AccountInterface

    init {
      engine = Proxy.newProxyInstance(
        AccountInterface::class.java.classLoader,
        arrayOf(AccountInterface::class.java),
      ) { proxy, method, args ->
        val name = method.name.substringBefore('-')
        val list = args?.toList() ?: emptyList()
        when (name) {
          "equals" -> proxy === list[0]
          "hashCode" -> System.identityHashCode(proxy)
          "toString" -> "FakeEngine"
          "noteBody" -> body
          "noteRunsJson" -> storedRuns
          "noteSaveFormatted" -> {
            saves += (list[1] as String) to (list[3] as String?)
            body = list[1] as String
            (list[3] as String?)?.let { storedRuns = it }
            Unit
          }
          "noteMarkdown" -> {
            markdownAsked += (list[0] as String) to (list[1] as String)
            "# " + (list[0] as String).lineSequence().first() + "\n"
          }
          "noteLockHint" -> null
          "noteLockForget" -> Unit
          "noteTableJson" -> null
          "noteAttachmentInfoJson" -> """{"id":"A-1","uti":"public.png","kind":"image","filename":"Lemons.png"}"""
          "noteMedia" -> picture
          "close" -> Unit
          else -> when (method.returnType) {
            Void.TYPE -> Unit
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
          }
        }
      } as AccountInterface
    }

    /** What another device wrote at the end of the note meanwhile, its formatting with it. */
    fun append(more: String) {
      body += more
      val runs = org.json.JSONArray(storedRuns)
      val last = runs.getJSONObject(runs.length() - 1)
      last.put("length", last.getInt("length") + more.length)
      storedRuns = runs.toString()
    }
  }

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  /** The app's theme for Notes, light or dark as asked, whatever the emulator is set to. */
  @Composable
  private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    val base = LocalConfiguration.current
    val config = Configuration(base).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
        (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    CompositionLocalProvider(LocalConfiguration provides config) {
      SkykeepTheme(dark = dark) { AppTheme(Palette.App.Notes) { content() } }
    }
  }

  private fun shot(name: String) {
    Thread.sleep(800)
    compose.waitForIdle()
    val dir = context.getExternalFilesDir(null) ?: return
    val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
    File(dir, "chrome-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun findWeb(view: View): WebView? = when (view) {
    is WebView -> view
    is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
    else -> null
  }

  private fun web(): WebView {
    compose.waitUntil(10_000) { findWeb(compose.activity.window.decorView) != null }
    return findWeb(compose.activity.window.decorView)!!
  }

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

  private fun waitForJs(script: String, timeoutMs: Long = 20_000, check: (String) -> Boolean): String {
    val end = System.currentTimeMillis() + timeoutMs
    var last = ""
    while (System.currentTimeMillis() < end) {
      compose.waitForIdle()
      last = js(script)
      if (check(last)) return last
      Thread.sleep(120)
    }
    throw AssertionError("timed out waiting for $script (last answer: $last)")
  }

  /** The bar alone, wired to an editor state whose page records what it is told. */
  private fun bar(dark: Boolean = false): Pair<NoteEditorState, RecordingPage> {
    val state = NoteEditorState()
    val page = RecordingPage()
    state.page = page
    compose.setContent {
      Themed(dark) {
        val status by state.status.collectAsState()
        FormatBar(status, stylesOpen = false, onStyles = {}, onCommand = state::command)
      }
    }
    return state to page
  }

  @Test
  fun the_bar_s_bold_sends_command_bold_and_lights_from_status() {
    val (state, page) = bar()

    compose.onNodeWithContentDescription("Bold").assertIsOff().performClick()
    assertEquals(listOf("bold" to null), page.commands.toList())

    // The page reports the caret in bold: the button is lit, and says so to TalkBack.
    compose.runOnUiThread { state.event("host_status", JSONObject("""{"bold":true,"canUndo":true}""")) }
    compose.onNodeWithContentDescription("Bold").assertIsOn()
    compose.onNodeWithContentDescription("Italic").assertIsOff()

    // The row scrolls sideways on a narrow screen: the later buttons are brought into view first.
    compose.onNodeWithContentDescription("Decrease Indent").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Move Line Down").performScrollTo().performClick()
    compose.onNodeWithContentDescription("Undo").performScrollTo().performClick()
    // Redo has nothing to do: it is there but off.
    compose.onNodeWithContentDescription("Redo").performScrollTo().assertIsNotEnabled().performClick()
    assertEquals(listOf("bold", "outdent", "moveDown", "undo"), page.commands.map { it.first })
  }

  @Test
  fun the_styles_sheet_ticks_the_current_style() {
    val state = NoteEditorState()
    val page = RecordingPage()
    state.page = page
    compose.setContent {
      Themed(dark = false) {
        val status by state.status.collectAsState()
        StyleSheet(status, state::command)
      }
    }

    // No style is Body.
    compose.onNode(hasText("Body")).assertIsSelected()
    compose.onNode(hasText("Heading")).assertIsNotSelected()
    compose.onNode(hasContentDescription("No colour")).assertIsSelected()

    compose.runOnUiThread { state.event("host_status", JSONObject("""{"style":1,"colour":3,"quote":true}""")) }
    compose.onNode(hasText("Heading")).assertIsSelected()
    compose.onNode(hasText("Body")).assertIsNotSelected()
    compose.onNode(hasContentDescription("Orange")).assertIsSelected()
    compose.onNodeWithContentDescription("Block Quote").assertIsOn()

    compose.onNode(hasText("Title")).performClick()
    compose.onNode(hasText("Body")).performClick()
    compose.onNode(hasContentDescription("Mint")).performClick()
    compose.onNode(hasContentDescription("No colour")).performClick()
    assertEquals(listOf("style" to 0, "style" to null, "colour" to 4, "colour" to null), page.commands.toList())
  }

  /** The engine behind the note `editing` opens, for the tests that read what was saved. */
  private lateinit var editingFake: FakeEngine

  /**
   * The note open with its bar, as NoteScreen draws it: the editor focused (and the keyboard up
   * where the phone shows one), then Bold tapped. The editor keeps the focus, and the page's
   * answer lights the button.
   */
  private fun editing(dark: Boolean): NoteEditorState {
    val rest = "Shortcrust: flour, butter, sugar\nCurd: lemons, eggs, sugar, butter\nBake 25 minutes at 180°\n"
    // The title line in Notes' Title style, the rest body: runs that describe the text exactly.
    val fake = FakeEngine("Lemon tart\n$rest", """[{"length":11,"style":0},{"length":${rest.length}}]""")
    editingFake = fake
    val state = NoteEditorState()
    // As MainActivity runs: edge to edge, and the keyboard resizing (the manifest's adjustResize).
    compose.runOnUiThread {
      compose.activity.enableEdgeToEdge()
      compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    compose.setContent {
      Themed(dark) {
        // The searched word, marked in the note as when it is opened from search results.
        NoteEditingArea(fake.engine, state, "N-1", null, editable = true, locked = false, found = listOf("lemons"), modifier = Modifier.fillMaxSize().statusBarsPadding())
      }
    }
    compose.waitUntil(30_000) { state.ready.value }
    waitForJs("document.querySelector('.ProseMirror')?.innerText ?? ''") { it.contains("Curd") }
    js("document.querySelector('.ProseMirror')?.focus()")
    compose.runOnUiThread {
      val view = web()
      view.requestFocus()
      context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
    }
    compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("format-bar")).fetchSemanticsNodes().isNotEmpty() }
    // Where the phone shows a keyboard, the bar sits on it, not behind it.
    Thread.sleep(1_500)
    compose.waitForIdle()
    var keyboardTop = Int.MAX_VALUE
    compose.runOnUiThread {
      val root = compose.activity.window.decorView
      val ime = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
      if (ime > 0) keyboardTop = root.height - ime
    }
    val barBottom = compose.onNode(hasTestTag("format-bar")).fetchSemanticsNode().boundsInWindow.bottom
    assertTrue("the bar ($barBottom) is under the keyboard ($keyboardTop)", barBottom <= keyboardTop + 1)
    return state
  }

  @Test
  fun buttons_do_not_take_focus() {
    editing(dark = false)

    compose.onNodeWithContentDescription("Bold").performClick()

    // The page's caret is still in the note, and the WebView still holds Android's focus.
    assertEquals("true", js("document.activeElement?.classList.contains('ProseMirror') === true"))
    var focused = false
    compose.runOnUiThread { focused = web().hasFocus() }
    assertTrue("the WebView lost the focus", focused)
    // The page took the command and reported it back: Bold is lit.
    compose.waitUntil(10_000) {
      runCatching { compose.onNodeWithContentDescription("Bold").assertIsOn() }.isSuccess
    }
    shot("format-bar-light")

    compose.onNodeWithContentDescription("Text Styles").performClick()
    compose.onNode(hasText("Heading")).assertExists()
    assertEquals("true", js("document.activeElement?.classList.contains('ProseMirror') === true"))
    shot("styles-sheet-light")
  }

  @Test
  fun the_bar_and_the_sheet_in_dark() {
    editing(dark = true)
    shot("format-bar-dark")
    compose.onNodeWithContentDescription("Text Styles").performClick()
    compose.onNode(hasText("Heading")).assertExists()
    shot("styles-sheet-dark")
  }

  /** Whether the phone's keyboard is up over the note. */
  private fun keyboardShown(): Boolean {
    var shown = false
    compose.runOnUiThread {
      val root = compose.activity.window.decorView
      shown = (ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0
    }
    return shown
  }

  /** A key through Android's own input, as the phone's keyboard hands it over. */
  private fun key(code: Int) {
    shell("input keyevent $code")
    Thread.sleep(120)
  }

  /** Letters typed the way the keyboard types them — `input text`, never a script's insertText. */
  private fun typeThroughKeyboard(text: String) {
    shell("input text $text")
    Thread.sleep(120)
  }

  /** A shell command through the device, its output drained so it never waits on us. */
  private fun shell(command: String) {
    val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    java.io.FileInputStream(out.fileDescriptor).use { it.readBytes() }
    out.close()
  }

  /** Whether `index` of the note's text is bold in the runs as the page sent them. */
  private fun boldAt(runsJson: String, index: Int): Boolean {
    var at = 0
    val runs = org.json.JSONArray(runsJson)
    for (i in 0 until runs.length()) {
      val run = runs.getJSONObject(i)
      val length = run.optInt("length", 0)
      if (index >= at && index < at + length) return run.optBoolean("bold", false)
      at += length
    }
    return false
  }

  /**
   * A format chosen with nothing selected holds for everything typed next: Bold tapped on a new
   * empty line, the pause the owner took, then the word typed with the keyboard — the capital
   * letter it decided on included. Nothing landing in between may take the format away.
   */
  @Test
  fun a_format_chosen_on_an_empty_line_is_held_for_the_whole_word() {
    val state = editing(dark = false)
    assertTrue("the keyboard was down before the word", keyboardShown())

    // The caret to the end of the note and a new, empty line, with the arrows and Return.
    key(123) // End
    repeat(4) { key(20) } // Down, line by line
    key(66) // Enter
    compose.waitForIdle()

    // Something typed before, so that a save of it lands while the format is chosen.
    typeThroughKeyboard("firstline")
    waitForJs("document.querySelector('.ProseMirror')?.innerText ?? ''") { it.contains("firstline") }
    key(66) // Enter: a new, empty line
    compose.waitForIdle()

    compose.onNodeWithContentDescription("Bold").performClick()
    // The pause the owner took: the save of the line above lands, and the note is read again
    // as it is after a sync — here changed on another device.
    compose.waitUntil(20_000) { editingFake.saves.isNotEmpty() }
    Thread.sleep(200)
    editingFake.append("Written on the iPhone.\n")
    state.refresh()
    // The re-read was taken in: the note shows what the other device wrote.
    waitForJs("document.querySelector('.ProseMirror')?.innerText ?? ''") { it.contains("Written on the iPhone.") }
    Thread.sleep(300)

    // The keys the owner typed next: the capital the keyboard makes of the first letter.
    typeThroughKeyboard("Boldword")
    waitForJs("document.querySelector('.ProseMirror')?.innerText ?? ''") { it.contains("Boldword") }
    // The format chosen is still the one the bar shows, and the caret holds.
    compose.onNodeWithContentDescription("Bold").assertIsOn()
    runBlocking { state.flush() }

    val save = editingFake.saves.last()
    val at = save.first.indexOf("Boldword")
    assertTrue("the word is not in ${save.first}", at >= 0)
    val runs = save.second
    assertNotNull("the note was saved without its runs", runs)
    for (i in 0 until 8) {
      assertTrue(
        "character $i of Boldword is not bold in ${runs} (text ${save.first})",
        boldAt(runs!!, at + i),
      )
    }
  }

  @Test
  fun export_writes_the_engine_s_markdown_to_the_chosen_document() {
    val fake = FakeEngine("Lemon tart\nflour\n", """[{"length":11,"style":0},{"length":6}]""")
    val out = File(context.cacheDir, "export-test/chosen.md").apply {
      parentFile?.mkdirs()
      writeText("what was there before, longer than what replaces it")
    }

    val export = noteExport(fake.engine, "N-1")
    writeMarkdown(context, Uri.fromFile(out), export.markdown)

    assertEquals("Lemon tart.md", export.name)
    // The engine was asked with the note's own text and runs, and its answer is the file.
    assertEquals(listOf("Lemon tart\nflour\n" to """[{"length":11,"style":0},{"length":6}]"""), fake.markdownAsked.toList())
    assertEquals("# Lemon tart\n", out.readText())
  }

  @Test
  fun attach_copies_into_the_cache_and_calls_attach_with_that_path() {
    val picked = File(context.filesDir, "attach-test/Lemons.png").apply {
      parentFile?.mkdirs()
      writeBytes(png(64, 48))
    }
    val state = NoteEditorState()
    val page = RecordingPage()
    state.page = page

    val copy = runBlocking { attachPicked(context, state, Uri.fromFile(picked)) }

    assertTrue(copy.path, copy.path.startsWith(File(context.cacheDir, "attach").path))
    assertEquals("Lemons.png", copy.name)
    assertArrayEquals(picked.readBytes(), copy.readBytes())
    assertEquals(listOf(copy.path), page.attached.toList())
  }

  @Test
  fun a_picture_opens_full_screen() {
    val fake = FakeEngine("", "[]").apply { picture = png(1200, 800) }
    File(context.cacheDir, "note-shown").deleteRecursively()
    compose.setContent {
      Themed(dark = false) {
        AttachmentViewer(fake.engine, OpenedAttachment("A-1", "image", "Lemons.png")) {}
      }
    }
    compose.waitUntil(10_000) {
      compose.onAllNodes(hasContentDescription("Lemons.png")).fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithContentDescription("Close").assertExists()
    shot("picture-viewer")
  }

  /** A small picture: a lemon-to-sky gradient, so a screenshot shows it drawn. */
  private fun png(width: Int, height: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
      shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFFF2B33D.toInt(), 0xFF2F9BFF.toInt(), Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
  }
}

