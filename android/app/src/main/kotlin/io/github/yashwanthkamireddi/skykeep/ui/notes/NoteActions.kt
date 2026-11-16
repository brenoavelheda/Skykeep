package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.yashwanthkamireddi.skykeep.R
import io.github.yashwanthkamireddi.skykeep.sync.DriveFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.quince_mobile.AccountInterface

/** What a file name cannot hold on the desktop's file systems, replaced as the desktop replaces it. */
private val UNSAFE = Regex("[\\\\/:*?\"<>|]")

/**
 * The name an exported note is offered under: its first line (or "Note"), without what a file
 * name cannot hold, at most 80 characters (never half an emoji), as the desktop names it.
 */
internal fun markdownFileName(text: String): String {
  val first = text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Note" }
  val clean = first.replace(UNSAFE, "-")
  var cut = clean.take(80)
  if (cut.length < clean.length && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
  return "$cut.md"
}

/** The words searched for, as the page takes them to mark in the note. */
internal fun foundWords(query: String): List<String> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

/** A note as Markdown and the name it is offered under. */
internal class NoteExport(val name: String, val markdown: String)

/**
 * The note as the library holds it now, in Markdown, by the desktop's own conversion
 * (`note_markdown`, the same `note_to_markdown`). Asked after a flush, so what was typed is in it.
 */
internal fun noteExport(engine: AccountInterface, guid: String): NoteExport {
  val body = engine.noteBody(guid).orEmpty()
  return NoteExport(markdownFileName(body), engine.noteMarkdown(body, engine.noteRunsJson(guid)))
}

/** Writes the Markdown to the document the person chose, replacing whatever it held. */
internal fun writeMarkdown(context: Context, uri: Uri, markdown: String) {
  val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("The document can't be written.")
  out.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
}

/**
 * A picture or file the person picked, copied into the app's cache under its own name: the
 * engine reads it from there (`note_attach_file`, a path). Copies from earlier than a day ago go.
 */
internal fun attachCopy(context: Context, uri: Uri): File {
  val resolver = context.contentResolver
  val root = File(context.cacheDir, "attach")
  val dayAgo = System.currentTimeMillis() - 86_400_000
  root.listFiles()?.filter { it.lastModified() < dayAgo }?.forEach { it.deleteRecursively() }
  val shown = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    }
  }.getOrNull() ?: uri.lastPathSegment
  // A name without its kind is given one from what the picker says it is: the engine tells a
  // picture from a file by it.
  val ext = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
  val name = DriveFiles.safeName(shown ?: "Attachment").let { if ('.' !in it && ext != null) "$it.$ext" else it }
  val file = File(root, "${UUID.randomUUID()}/$name").also { it.parentFile?.mkdirs() }
  val input = resolver.openInputStream(uri) ?: error("The file can't be read.")
  input.use { from -> file.outputStream().use { from.copyTo(it) } }
  return file
}

/** A picked picture or file added to the note: copied into the cache, then handed to the page at its caret. */
internal suspend fun attachPicked(context: Context, state: NoteEditorState, uri: Uri): File {
  val copy = withContext(Dispatchers.IO) { attachCopy(context, uri) }
  state.attach(copy.path)
  return copy
}

/** The note as a Markdown file for another app, through the share sheet: the copy made, the sheet's intent. */
private fun markdownShare(context: Context, guid: String, export: NoteExport): Intent {
  val file = File(context.cacheDir, "share/${DriveFiles.keyOf(guid)}/${DriveFiles.safeName(export.name)}").also { it.parentFile?.mkdirs() }
  file.writeText(export.markdown)
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
  val send = Intent(Intent.ACTION_SEND).setType("text/markdown")
    .putExtra(Intent.EXTRA_STREAM, uri)
    .putExtra(Intent.EXTRA_SUBJECT, export.name.removeSuffix(".md"))
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Undo and Redo for the note, in the top bar as on the iPhone: lit when the page has one to do. */
@Composable
fun NoteUndoRedo(state: NoteEditorState) {
  val status by state.status.collectAsState()
  val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
  IconButton(onClick = { state.command("undo") }, enabled = status.canUndo) {
    Icon(painterResource(R.drawable.ic_text_undo), contentDescription = "Undo", tint = if (status.canUndo) MaterialTheme.colorScheme.onSurfaceVariant else off)
  }
  IconButton(onClick = { state.command("redo") }, enabled = status.canRedo) {
    Icon(painterResource(R.drawable.ic_text_redo), contentDescription = "Redo", tint = if (status.canRedo) MaterialTheme.colorScheme.onSurfaceVariant else off)
  }
}

/** Lock Now, on a locked note: every opened note locked again and the passwords forgotten, as on the desktop. */
@Composable
fun NoteLockNow(state: NoteEditorState) {
  TextButton(onClick = { state.command("lockNow") }) {
    Icon(painterResource(R.drawable.ic_lock_open), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text("Lock Now")
  }
}

/**
 * The note's other actions, behind the top bar's menu: Export as Markdown (to a document the
 * person picks, or shared) and, on a note that can be written, Add Photo and Add File. Each
 * export first has the page save what is typed, so the file holds what is on screen; each
 * picked file is copied into the cache and handed to the page, which adds it at the caret.
 */
@Composable
fun NoteMoreMenu(
  engine: AccountInterface,
  state: NoteEditorState,
  canExport: Boolean,
  canAttach: Boolean,
  onProblem: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var menu by remember { mutableStateOf(false) }
  // The note being exported while the document picker is up, kept through a rotation.
  var exporting by rememberSaveable { mutableStateOf<String?>(null) }

  val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
    val guid = exporting
    exporting = null
    if (uri != null && guid != null) scope.launch {
      runCatching { withContext(Dispatchers.IO) { writeMarkdown(context, uri, noteExport(engine, guid).markdown) } }
        .onFailure { onProblem("The note couldn't be exported.") }
    }
  }
  fun attach(uri: Uri?) {
    uri ?: return
    scope.launch {
      runCatching { attachPicked(context, state, uri) }.onFailure { onProblem("That file couldn't be added.") }
    }
  }
  val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { attach(it) }
  val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { attach(it) }

  /** What is typed saved first, then the note as Markdown; null (and said) when it can't be read. */
  suspend fun exported(): Pair<String, NoteExport>? {
    state.flush()
    val guid = state.guid.value ?: return null
    return runCatching { withContext(Dispatchers.IO) { guid to noteExport(engine, guid) } }
      .onFailure { onProblem("The note couldn't be exported.") }
      .getOrNull()
  }

  if (!canExport && !canAttach) return
  Box {
    IconButton(onClick = { menu = true }) {
      Icon(painterResource(R.drawable.ic_more), contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      if (canExport) {
        DropdownMenuItem(
          text = { Text("Export as Markdown") },
          leadingIcon = { Icon(painterResource(R.drawable.ic_file_text), contentDescription = null) },
          onClick = {
            menu = false
            scope.launch {
              val (guid, export) = exported() ?: return@launch
              exporting = guid
              create.launch(export.name)
            }
          },
        )
        DropdownMenuItem(
          text = { Text("Share as Markdown") },
          leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) },
          onClick = {
            menu = false
            scope.launch {
              val (guid, export) = exported() ?: return@launch
              runCatching { context.startActivity(withContext(Dispatchers.IO) { markdownShare(context, guid, export) }) }
                .onFailure { onProblem("The note couldn't be shared.") }
            }
          },
        )
      }
      if (canAttach) {
        DropdownMenuItem(
          text = { Text("Add Photo") },
          leadingIcon = { Icon(painterResource(R.drawable.ic_image_plus), contentDescription = null) },
          onClick = {
            menu = false
            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
          },
        )
        DropdownMenuItem(
          text = { Text("Add File") },
          leadingIcon = { Icon(painterResource(R.drawable.ic_paperclip), contentDescription = null) },
          onClick = {
            menu = false
            document.launch(arrayOf("*/*"))
          },
        )
      }
    }
  }
}
