package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val saves = CopyOnWriteArrayList<Save>()
    val unlocks = CopyOnWriteArrayList<String?>()
    var picture: ByteArray = ByteArray(0)
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
          "noteRunsJson" -> runs
          "noteSaveFormatted" -> {
            saves += Save(list[0] as String, list[1] as String, list[2] as String?, list[3] as String?, list[4] as String?)
            body = list[1] as String
            Unit
          }
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
          "noteAttachmentInfoJson" -> """{"id":"A-1","uti":"public.jpeg","kind":"image","filename":"IMG.jpg"}"""
          "noteMedia" -> picture
          "close" -> Unit
          else -> fallback(method.returnType)
        }
      } as AccountInterface
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

  private fun show(guid: String?, locked: Boolean = false, editable: Boolean = true) {
    state = NoteEditorState()
    compose.setContent {
      SkykeepTheme {
        // As NoteScreen leaves the note: what is typed is saved, then the note is gone.
        var left by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        if (left) {
          Text("left")
        } else {
          BackHandler { scope.launch { state.flush(); left = true } }
          NoteWebEditor(fake.engine, state, guid, null, editable, locked, emptyList(), Modifier.fillMaxSize())
        }
      }
    }
    compose.waitUntil(30_000) { state.ready.value }
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
}
