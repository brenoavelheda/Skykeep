package io.github.yashwanthkamireddi.skykeep.ui.notes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.quince_mobile.AccountInterface
import uniffi.quince_mobile.EngineException

/**
 * One message the note page posted: the envelope is `{"id", "cmd", "args"}`
 * (src/mobile/notes/BRIDGE.md). `id > 0` is a command, answered exactly once; `id = 0` is an
 * event, told and never answered.
 */
internal sealed interface NoteMessage {
  class Command(val id: Long, val cmd: String, val args: JSONObject) : NoteMessage
  class Event(val cmd: String, val args: JSONObject) : NoteMessage
  /** Not the envelope this app knows: refused when it named a command to answer. */
  class Unreadable(val id: Long?, val reason: String) : NoteMessage
}

/** A command's answer: its payload as JSON text, or the message to show when it was refused. */
internal class NoteAnswer(val id: Long, val ok: Boolean, val payload: String)

/**
 * The note page's commands, carried to the engine as the desktop's Tauri commands carry them:
 * the name is the desktop's and so are the argument names (`src/lib/ipc.ts`). Runs cross as the
 * JSON text of the desktop's `Run` shape and are never read here; nothing here decides when a
 * note saves — the page keeps that session and this only executes what it asks.
 *
 * Android-free on purpose: the JVM tests exercise every command without a WebView.
 */
internal class NoteHost(
  private val engine: AccountInterface,
  private val clipboard: () -> String? = { null },
) {
  fun read(json: String): NoteMessage = try {
    val message = JSONObject(json)
    val id = message.optLong("id", 0)
    val cmd = message.optString("cmd", "")
    val args = message.optJSONObject("args") ?: JSONObject()
    when {
      cmd.isEmpty() -> NoteMessage.Unreadable(null, "The note editor sent something this app doesn't know.")
      id > 0 -> NoteMessage.Command(id, cmd, args)
      else -> NoteMessage.Event(cmd, args)
    }
  } catch (e: Exception) {
    NoteMessage.Unreadable(null, "The note editor's message couldn't be read.")
  }

  /** Runs one command on the engine and answers it: a refusal comes back as its words. */
  suspend fun answer(command: NoteMessage.Command): NoteAnswer = try {
    NoteAnswer(command.id, true, withContext(Dispatchers.IO) { run(command.cmd, command.args) })
  } catch (e: CancellationException) {
    throw e
  } catch (e: EngineException.Failed) {
    // The engine's own words: shown to the person, and the lock screen reads them too.
    NoteAnswer(command.id, false, JSONObject.quote(e.reason))
  } catch (e: Throwable) {
    NoteAnswer(command.id, false, JSONObject.quote(e.message ?: "The note couldn't be reached."))
  }

  private fun run(cmd: String, args: JSONObject): String = when (cmd) {
    "note_body" -> jsonString(engine.noteBody(args.getString("guid")))
    "note_runs" -> engine.noteRunsJson(args.getString("guid"))
    "note_save" -> {
      val guid = args.getString("guid")
      engine.noteSaveFormatted(
        guid,
        args.getString("body"),
        args.string("base"),
        args.raw("runs"),
        args.raw("baseRuns"),
      )
      // What the library holds now: the page keeps its next save's base on it.
      jsonString(engine.noteBody(guid))
    }
    "note_create" -> jsonString(engine.noteCreate(args.string("folderGuid"), args.getString("body")))
    "note_table" -> engine.noteTableJson(args.getString("id")) ?: "null"
    "note_table_set_cell" -> engine.noteTableSetCell(
      args.getString("id"), args.getInt("row").toUInt(), args.getInt("column").toUInt(), args.getString("text"),
    )
    "note_attachment_info" -> engine.noteAttachmentInfoJson(args.getString("id"))
    "note_unlock" -> engine.noteUnlockJson(args.getString("guid"), args.string("password"))
    "note_lock_hint" -> jsonString(engine.noteLockHint(args.getString("guid")))
    "note_lock_forget" -> {
      engine.noteLockForget()
      "null"
    }
    "note_attach_file" -> engine.noteAttachFileJson(
      args.getString("guid"), args.getString("path"), args.getInt("atOffset").toUInt(), args.string("base"),
    )
    "clipboard_read" -> JSONObject()
      .put("files", org.json.JSONArray())
      .put("text", clipboard() ?: JSONObject.NULL)
      .toString()
    else -> throw Refused("This app doesn't know the note editor's \"$cmd\".")
  }

  /** A string as JSON, or `null`. */
  private fun jsonString(value: String?): String = if (value == null) "null" else JSONObject.quote(value)

  /** A stretch of the message written back as JSON text: runs travel as their own shape. */
  private fun JSONObject.raw(name: String): String? = when (val value = opt(name)) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
  }

  /** A string, or `null` when the page sent none. JSON's null is not the word "null". */
  private fun JSONObject.string(name: String): String? = when (val value = opt(name)) {
    null, JSONObject.NULL -> null
    is String -> value
    else -> value.toString()
  }

  private class Refused(message: String) : Exception(message)
}
