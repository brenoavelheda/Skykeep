package io.github.yashwanthkamireddi.skykeep.ui.notes

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.quince_mobile.AccountInterface
import uniffi.quince_mobile.EngineException

/**
 * The dispatcher the note page talks to: each of BRIDGE.md's commands reaches the engine method
 * the desktop's own command reaches, with its arguments as sent, and the answer has the shape
 * the page reads. A fake `AccountInterface` stands in for the engine: nothing here signs in or
 * touches a note.
 */
class NoteHostTest {
  /** What the fake engine was asked, in order: the method's name and its arguments. */
  private class Calls {
    val made = mutableListOf<Pair<String, List<Any?>>>()
    fun args(name: String): List<Any?> = made.filter { it.first == name }.single().second
    fun count(name: String): Int = made.count { it.first == name }
  }

  /** An `AccountInterface` that records every call and answers the few it is told to. */
  private fun engine(answers: Map<String, (List<Any?>) -> Any?>): Pair<AccountInterface, Calls> {
    val calls = Calls()
    val fake = Proxy.newProxyInstance(
      AccountInterface::class.java.classLoader,
      arrayOf(AccountInterface::class.java),
    ) { proxy, method, args ->
      // A method taking an unsigned value has a hash after its name on the JVM; the name the
      // engine exports is the part before it.
      val name = method.name.substringBefore('-')
      val list = args?.toList() ?: emptyList()
      when (name) {
        "equals" -> proxy === list[0]
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "FakeAccount"
        else -> {
          calls.made += name to list
          answers[name]?.invoke(list) ?: fallback(method.returnType)
        }
      }
    } as AccountInterface
    return fake to calls
  }

  /** What a method nobody stubbed answers: nothing, as a phone that has not read it yet. */
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

  private fun command(cmd: String, vararg args: Pair<String, Any?>): String = JSONObject()
    .put("id", 7L)
    .put("cmd", cmd)
    .put("args", JSONObject().apply { for ((key, value) in args) put(key, value ?: JSONObject.NULL) })
    .toString()

  private fun answer(host: NoteHost, json: String): NoteAnswer =
    runBlocking { host.answer(host.read(json) as NoteMessage.Command) }

  @Test
  fun `a note's text is answered as JSON or null`() {
    val (engine, calls) = engine(mapOf("noteBody" to { _: List<Any?> -> "Groceries\nmilk" }))
    val host = NoteHost(engine)

    val answer = answer(host, command("note_body", "guid" to "N-1"))

    assertTrue(answer.ok)
    assertEquals("\"Groceries\\nmilk\"", answer.payload)
    assertEquals(listOf<Any?>("N-1"), calls.args("noteBody"))

    val (empty, _) = engine(mapOf("noteBody" to { _: List<Any?> -> null }))
    assertEquals("null", answer(NoteHost(empty), command("note_body", "guid" to "N-1")).payload)
  }

  @Test
  fun `the runs are the engine's own JSON`() {
    val runs = """[{"length":5,"bold":true}]"""
    val (engine, calls) = engine(mapOf("noteRunsJson" to { _: List<Any?> -> runs }))

    val answer = answer(NoteHost(engine), command("note_runs", "guid" to "N-1"))

    assertTrue(answer.ok)
    assertEquals(runs, answer.payload)
    assertEquals(listOf<Any?>("N-1"), calls.args("noteRunsJson"))
  }

  @Test
  fun `a save carries the text, the base and both runs, and answers the text kept`() {
    val (engine, calls) = engine(
      mapOf(
        "noteSaveFormatted" to { _: List<Any?> -> Unit },
        "noteBody" to { _: List<Any?> -> "Groceries\nmilk and eggs" },
      ),
    )

    val answer = answer(
      NoteHost(engine),
      command(
        "note_save",
        "guid" to "N-1",
        "body" to "Groceries\nmilk and eggs",
        "base" to "Groceries\nmilk",
        "runs" to """[{"length":19,"bold":true}]""",
        "baseRuns" to """[{"length":14}]""",
      ),
    )

    val call = calls.args("noteSaveFormatted")
    assertEquals("N-1", call[0])
    assertEquals("Groceries\nmilk and eggs", call[1])
    assertEquals("Groceries\nmilk", call[2])
    assertEquals("""[{"length":19,"bold":true}]""", call[3])
    assertEquals("""[{"length":14}]""", call[4])
    assertTrue(answer.ok)
    assertEquals("\"Groceries\\nmilk and eggs\"", answer.payload)
  }

  @Test
  fun `a save with no runs passes none, and none for its base either`() {
    val (engine, calls) = engine(mapOf("noteSaveFormatted" to { _: List<Any?> -> Unit }))

    answer(
      NoteHost(engine),
      command("note_save", "guid" to "N-1", "body" to "plain", "base" to null, "runs" to null, "baseRuns" to null),
    )

    val call = calls.args("noteSaveFormatted")
    assertNull(call[2])
    assertNull(call[3])
    assertNull(call[4])
  }

  @Test
  fun `a new note is made in its folder and answers its guid`() {
    val (engine, calls) = engine(mapOf("noteCreate" to { _: List<Any?> -> "NEW-1" }))

    val answer = answer(NoteHost(engine), command("note_create", "folderGuid" to "F-1", "body" to "Hello"))

    assertTrue(answer.ok)
    assertEquals("\"NEW-1\"", answer.payload)
    assertEquals(listOf<Any?>("F-1", "Hello"), calls.args("noteCreate"))
  }

  @Test
  fun `a table reads and writes one cell`() {
    val table = """[["a","b"],["c","d"]]"""
    val (engine, calls) = engine(
      mapOf(
        "noteTableJson" to { _: List<Any?> -> table },
        "noteTableSetCell" to { _: List<Any?> -> """[["a","b"],["c","Z"]]""" },
      ),
    )
    val host = NoteHost(engine)

    val read = answer(host, command("note_table", "id" to "T-1"))
    assertEquals(table, read.payload)
    assertEquals(listOf<Any?>("T-1"), calls.args("noteTableJson"))

    val write = answer(host, command("note_table_set_cell", "id" to "T-1", "row" to 1, "column" to 1, "text" to "Z"))
    assertEquals("""[["a","b"],["c","Z"]]""", write.payload)
    assertEquals(listOf<Any?>("T-1", 1, 1, "Z"), calls.args("noteTableSetCell"))  }

  @Test
  fun `a table never read answers null`() {
    val (engine, _) = engine(mapOf("noteTableJson" to { _: List<Any?> -> null }))
    assertEquals("null", answer(NoteHost(engine), command("note_table", "id" to "T-1")).payload)
  }

  @Test
  fun `an attachment's kind is answered as the desktop's record`() {
    val info = """{"id":"A-1","uti":"public.heic","kind":"image","filename":"IMG_0001.HEIC"}"""
    val (engine, calls) = engine(mapOf("noteAttachmentInfoJson" to { _: List<Any?> -> info }))

    val answer = answer(NoteHost(engine), command("note_attachment_info", "id" to "A-1"))

    assertEquals(info, answer.payload)
    assertEquals(listOf<Any?>("A-1"), calls.args("noteAttachmentInfoJson"))
  }

  @Test
  fun `a locked note opens with a password, or with the one already remembered`() {
    val opened = """{"body":"Secret","runs":[]}"""
    val (engine, calls) = engine(mapOf("noteUnlockJson" to { _: List<Any?> -> opened }))
    val host = NoteHost(engine)

    val typed = answer(host, command("note_unlock", "guid" to "N-1", "password" to "hunter2"))
    assertEquals(opened, typed.payload)
    assertEquals(listOf<Any?>("N-1", "hunter2"), calls.args("noteUnlockJson"))

    answer(host, command("note_unlock", "guid" to "N-1", "password" to null))
    assertNull(calls.made.last { it.first == "noteUnlockJson" }.second[1])
  }

  @Test
  fun `the hint is answered, and Lock Now forgets every password`() {
    val (engine, calls) = engine(mapOf("noteLockHint" to { _: List<Any?> -> "the usual" }, "noteLockForget" to { _: List<Any?> -> Unit }))
    val host = NoteHost(engine)

    assertEquals("\"the usual\"", answer(host, command("note_lock_hint", "guid" to "N-1")).payload)
    val forgotten = answer(host, command("note_lock_forget"))
    assertTrue(forgotten.ok)
    assertEquals("null", forgotten.payload)
    assertEquals(1, calls.count("noteLockForget"))

    val (none, _) = engine(mapOf("noteLockHint" to { _: List<Any?> -> null }))
    assertEquals("null", answer(NoteHost(none), command("note_lock_hint", "guid" to "N-1")).payload)
  }

  @Test
  fun `a file added is at the page's caret, over the text it shows`() {
    val added = """{"attachment":"A-2","media":"M","uti":"public.jpeg","offset":5,"body":"Trip\n\uFFFC\nend"}"""
    val (engine, calls) = engine(mapOf("noteAttachFileJson" to { _: List<Any?> -> added }))

    val answer = answer(
      NoteHost(engine),
      command("note_attach_file", "guid" to "N-1", "path" to "/cache/photo.jpg", "atOffset" to 5, "base" to "Trip\nend"),
    )

    assertEquals(added, answer.payload)
    assertEquals(listOf<Any?>("N-1", "/cache/photo.jpg", 5, "Trip\nend"), calls.args("noteAttachFileJson"))
  }

  @Test
  fun `the clipboard is the person's text, with no files`() {
    val host = NoteHost(engine(emptyMap()).first) { "https://example.com/" }

    val answer = answer(host, command("clipboard_read"))

    val payload = JSONObject(answer.payload)
    assertEquals(0, payload.getJSONArray("files").length())
    assertEquals("https://example.com/", payload.getString("text"))

    val (empty, _) = engine(emptyMap())
    val none = answer(NoteHost(empty) { null }, command("clipboard_read"))
    assertTrue(JSONObject(none.payload).isNull("text"))
  }

  @Test
  fun `the engine's refusal is the page's message`() {
    // A save the engine refuses as the phone's engine does: its words are the page's message.
    val (base, _) = engine(emptyMap())
    val host = NoteHost(Offline(base))

    val answer = answer(
      host,
      command("note_save", "guid" to "N-1", "body" to "x", "base" to "y", "runs" to null, "baseRuns" to null),
    )

    assertFalse(answer.ok)
    assertEquals("\"You're offline.\"", answer.payload)
  }

  /** An engine whose save is refused, as the signed-out phone's is. */
  private class Offline(base: AccountInterface) : AccountInterface by base {
    override fun `noteSaveFormatted`(guid: String, body: String, base: String?, runsJson: String?, baseRunsJson: String?) {
      throw EngineException.Failed("You're offline.")
    }
  }

  @Test
  fun `an unknown command is refused, never run`() {
    val (engine, calls) = engine(emptyMap())

    val answer = answer(NoteHost(engine), command("note_table_add_row", "id" to "T-1", "at" to 2))

    assertFalse(answer.ok)
    assertTrue(answer.payload.contains("doesn't know"))
    assertEquals(7L, answer.id)
    assertTrue(calls.made.isEmpty())
  }

  @Test
  fun `an event is read as one, and never answered`() {
    val host = NoteHost(engine(emptyMap()).first)

    val event = host.read("""{"id":0,"cmd":"host_ready","args":{}}""")
    assertTrue(event is NoteMessage.Event)
    assertEquals("host_ready", (event as NoteMessage.Event).cmd)

    val command = host.read("""{"id":12,"cmd":"note_body","args":{"guid":"N"}}""")
    assertTrue(command is NoteMessage.Command)
    assertEquals(12L, (command as NoteMessage.Command).id)

    assertTrue(host.read("not json") is NoteMessage.Unreadable)
    assertTrue(host.read("""{"id":3,"args":{}}""") is NoteMessage.Unreadable)
  }
}
