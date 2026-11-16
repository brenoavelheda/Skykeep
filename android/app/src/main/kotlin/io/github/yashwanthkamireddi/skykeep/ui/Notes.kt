package io.github.yashwanthkamireddi.skykeep.ui

import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.ContentResolver
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yashwanthkamireddi.skykeep.R
import io.github.yashwanthkamireddi.skykeep.sync.NOTES_AUTHORITY
import io.github.yashwanthkamireddi.skykeep.sync.Outcome
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteEditorState
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteWebEditor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.quince_mobile.Account
import uniffi.quince_mobile.AccountInterface
import uniffi.quince_mobile.NDoc
import uniffi.quince_mobile.NFolder
import uniffi.quince_mobile.NItem
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteListPrefs
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteOrder
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteSection
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteSort
import io.github.yashwanthkamireddi.skykeep.ui.notes.NoteThumb
import io.github.yashwanthkamireddi.skykeep.ui.notes.highlighted
import io.github.yashwanthkamireddi.skykeep.ui.notes.notePictures
import io.github.yashwanthkamireddi.skykeep.ui.notes.sections
import io.github.yashwanthkamireddi.skykeep.ui.notes.sorted
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import io.github.yashwanthkamireddi.skykeep.ui.theme.Palette

/** Apple's id for Recently Deleted (quince-core's TRASH_FOLDER). */
private const val TRASH = "TrashFolder-CloudKit"

/** Notes' own yellow, as the iCloud Notes app has it. */
private val NotesYellow = Color(Palette.NOTES)

private fun syncNotes(context: android.content.Context) {
  phoneAccount(context)?.let {
    ContentResolver.requestSync(it, NOTES_AUTHORITY, Bundle().apply {
      putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
      putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    })
  }
}

/** Where Notes is: the folders, one folder's notes (or all), or one note open. */
/** Where Notes is, kept through a rotation: kind, folder, folder title, note. */
private val NPlaceSaver = listSaver<NPlace, String>(
  save = { p ->
    when (p) {
      NPlace.Folders -> listOf("folders")
      is NPlace.Folder -> listOf("folder", p.guid ?: "", p.title)
      is NPlace.Open -> listOf("open", p.from.guid ?: "", p.from.title, p.guid ?: "")
    }
  },
  restore = { s ->
    when (s[0]) {
      "folder" -> NPlace.Folder(s[1].ifEmpty { null }, s[2])
      "open" -> NPlace.Open(s[3].ifEmpty { null }, NPlace.Folder(s[1].ifEmpty { null }, s[2]))
      else -> NPlace.Folders
    }
  },
)

private sealed interface NPlace {
  data object Folders : NPlace
  data class Folder(val guid: String?, val title: String) : NPlace
  /** A note open; `guid` null for a new one, made only once something is typed in it. */
  data class Open(val guid: String?, val from: Folder) : NPlace
}

/**
 * Notes, read from the phone's library: the folders with their counts, a folder's notes (pinned
 * first, then the latest), search, and a note open to read and edit. Every change shows at once
 * and is sent behind; a note's formatting is kept by the engine whatever is typed.
 */
@Composable
fun NotesPage(engine: AccountInterface) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var place by rememberSaveable(stateSaver = NPlaceSaver) { mutableStateOf<NPlace>(NPlace.Folders) }
  var folders by remember { mutableStateOf<List<NFolder>>(emptyList()) }
  var notes by remember { mutableStateOf<List<NItem>>(emptyList()) }
  var query by remember { mutableStateOf("") }
  var problem by remember { mutableStateOf<String?>(null) }
  var generation by remember { mutableIntStateOf(0) }
  var newFolder by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf<NFolder?>(null) }
  var dropping by remember { mutableStateOf<NFolder?>(null) }
  // How the list is ordered, kept across launches; each note's first picture held on the phone.
  var order by remember { mutableStateOf(NoteListPrefs.read(context)) }
  var viewing by remember { mutableStateOf(false) }
  var pictures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

  fun reason(t: Throwable) = (t as? uniffi.quince_mobile.EngineException.Failed)?.reason ?: "Notes couldn't be read."

  // The read in flight: a new one cancels it, so a search typed fast never shows an older
  // query's answer arriving after a newer one's.
  val reading = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
  fun reload(): kotlinx.coroutines.Job {
    reading[0]?.cancel()
    return scope.launch {
      val p = place
      try {
        val (f, n, pics) = withContext(Dispatchers.IO) {
          val f = engine.noteFolders()
          val n = when {
            query.isNotBlank() -> engine.searchNotes(query)
            p is NPlace.Folder -> engine.notes(p.guid)
            else -> emptyList()
          }
          // The rows' small pictures are a nicety: the list shows without them when they can't be read.
          val pics = if (n.isEmpty()) emptyMap() else runCatching { notePictures(engine.notePicturesJson()) }.getOrDefault(emptyMap())
          Triple(f, n, pics)
        }
        folders = f; notes = n; pictures = pics; problem = null
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (t: Throwable) {
        problem = reason(t)
      }
    }.also { reading[0] = it }
  }

  /** A folder made, renamed or deleted at Apple, then everything read again. */
  fun folderChange(what: String, write: () -> Unit) = scope.launch {
    runCatching { withContext(Dispatchers.IO) { write() } }.onFailure { problem = (it as? uniffi.quince_mobile.EngineException.Failed)?.reason ?: "$what didn't reach iCloud." }
    generation++
  }

  LaunchedEffect(Unit) { syncNotes(context) }
  DisposableEffect(Unit) {
    val stop = Outcome.watch(context) { generation++ }
    onDispose { stop() }
  }
  LaunchedEffect(place, query, generation) { reload() }
  // Back from a folder returns to the folders (the open note handles its own back: it saves).
  BackHandler(enabled = place is NPlace.Folder) { place = NPlace.Folders; query = "" }

  Column(Modifier.fillMaxSize()) {
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp, 12.dp)) }
    when (val p = place) {
      is NPlace.Open -> NoteScreen(engine, p.guid, p.from.guid, folders, onBack = { place = p.from; generation++ }, onProblem = { problem = it })
      else -> {
        val folder = p as? NPlace.Folder
        val trash = folder != null && folders.firstOrNull { it.guid == folder.guid }?.trash == true
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          if (folder != null) IconButton(onClick = { place = NPlace.Folders; query = "" }) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Folders") }
          Spacer(Modifier.weight(1f))
          if (folder != null && !trash) {
            // Nothing is made yet: a new note is made in iCloud when something is typed in it,
            // so one opened and left empty leaves nothing behind, as in Notes.
            IconButton(onClick = { place = NPlace.Open(null, folder) }) {
              Icon(painterResource(R.drawable.ic_edit), contentDescription = "New Note", tint = MaterialTheme.colorScheme.primary)
            }
          }
          // Every folder: View Options. A folder of the person's own (not All iCloud, Notes' own,
          // or Recently Deleted) also Rename, Delete.
          val own = folders.firstOrNull { it.guid == folder?.guid && !it.trash }
          if (folder != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
              IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), contentDescription = "Folder options") }
              DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("View Options") }, onClick = { menu = false; viewing = true })
                if (own != null) {
                  DropdownMenuItem(text = { Text("Rename Folder") }, onClick = { menu = false; renaming = own })
                  DropdownMenuItem(text = { Text("Delete Folder", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; dropping = own })
                }
              }
            }
          }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
          Text(folder?.title ?: "Notes", style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (folder != null && query.isBlank()) {
            Text(if (notes.size == 1) "1 note" else "${notes.size} notes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        SearchPill(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), cursor = MaterialTheme.colorScheme.primary)
        if (folder == null && query.isBlank()) {
          FolderList(folders, onNewFolder = { newFolder = true }) { place = it }
        } else {
          NoteList(engine, notes, order, query, pictures) { n -> place = NPlace.Open(n.guid, folder ?: NPlace.Folder(null, "All iCloud")) }
        }
      }
    }
  }
  if (viewing) ViewOptions(order, onDismiss = { viewing = false }) { order = it; NoteListPrefs.write(context, it) }
  if (newFolder) NameDialog("New Folder", "", onDismiss = { newFolder = false }) { name ->
    newFolder = false
    folderChange("Making $name") { engine.noteFolderCreate(name) }
  }
  renaming?.let { f ->
    NameDialog("Rename Folder", f.title, onDismiss = { renaming = null }) { name ->
      renaming = null
      if (name != f.title) {
        place = NPlace.Folder(f.guid, name)
        folderChange("Renaming ${f.title}") { engine.noteFolderRename(f.guid, name) }
      }
    }
  }
  dropping?.let { f ->
    AlertDialog(
      onDismissRequest = { dropping = null },
      title = { Text("Delete “${f.title}”?") },
      text = { Text("Its notes move to Recently Deleted on all your devices, where they stay for 30 days.") },
      confirmButton = {
        TextButton(onClick = {
          dropping = null; place = NPlace.Folders
          folderChange("Deleting ${f.title}") { engine.noteFolderDelete(f.guid) }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { dropping = null }) { Text("Cancel") } },
    )
  }
}

/** The folders, as the desktop's sidebar lists them: All iCloud, each folder, Recently Deleted. */
@Composable
private fun FolderList(folders: List<NFolder>, onNewFolder: () -> Unit, open: (NPlace.Folder) -> Unit) {
  val all = folders.filter { !it.trash }.sumOf { it.count.toInt() }
  data class Row(val title: String, val count: Int, val icon: Int, val place: NPlace.Folder)
  val rows = buildList {
    add(Row("All iCloud", all, R.drawable.ic_folder, NPlace.Folder(null, "All iCloud")))
    // Apple's own "Notes" folder is the one with no folder: notes not filed anywhere.
    folders.filter { !it.trash }.forEach { add(Row(it.title, it.count.toInt(), R.drawable.ic_folder, NPlace.Folder(it.guid, it.title))) }
    folders.firstOrNull { it.trash }?.let { add(Row("Recently Deleted", it.count.toInt(), R.drawable.ic_trash, NPlace.Folder(it.guid, "Recently Deleted"))) }
  }
  LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
    item {
      GroupCard(rows, inset = 54) { r ->
        androidx.compose.foundation.layout.Row(
          Modifier.fillMaxWidth().clickable { open(r.place) }.padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(painterResource(r.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(16.dp))
          Text(r.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text("${r.count}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(Modifier.width(6.dp))
          Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
      }
    }
    item {
      TextButton(onClick = onNewFolder, modifier = Modifier.padding(top = 8.dp)) {
        Icon(painterResource(R.drawable.ic_folder_plus), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("New Folder")
      }
    }
  }
}

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** When a note was changed, as Notes says it: the time today, Yesterday, the weekday this week, else the date. */
private fun changed(at: Long?): String {
  at ?: return ""
  val z = Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault())
  val today = LocalDate.now()
  val d = z.toLocalDate()
  return when {
    d == today -> z.toLocalTime().format(timeFormat)
    d == today.minusDays(1) -> "Yesterday"
    d.isAfter(today.minusDays(7)) -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    else -> d.format(dayFormat)
  }
}

/**
 * Sort By and Group By Date, as the desktop's View menu offers them; each choice applied and
 * kept at once. Grouping is not offered by title, which Notes never groups.
 */
@Composable
private fun ViewOptions(order: NoteOrder, onDismiss: () -> Unit, change: (NoteOrder) -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("View Options") },
    text = {
      Column(Modifier.selectableGroup()) {
        Text("Sort By", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        for (by in NoteSort.entries) {
          Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = order.by == by, role = Role.RadioButton) { change(order.copy(by = by)) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = order.by == by, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(by.label, style = MaterialTheme.typography.bodyLarge)
          }
        }
        if (order.by != NoteSort.Title) {
          HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
          Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(order.groupByDate, role = Role.Switch) { change(order.copy(groupByDate = it)) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("Group By Date", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = order.groupByDate, onCheckedChange = null)
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
  )
}

/**
 * Notes as the desktop lists them: in the chosen order, grouped by date unless that is off
 * (Pinned first), each section one card, each note its title in bold over its time and first
 * line, its first picture at the end when the phone holds one. Search results are one list,
 * the searched words lit in each title and line.
 */
@Composable
private fun NoteList(engine: AccountInterface, notes: List<NItem>, order: NoteOrder, query: String, pictures: Map<String, String>, open: (NItem) -> Unit) {
  val shown = remember(notes, order, query) {
    if (query.isBlank()) sections(notes, order) else listOf(NoteSection("", sorted(notes, order)))
  }
  // The searched words in Notes' yellow, the text on it at full strength so it reads in both themes.
  val mark = SpanStyle(background = NotesYellow.copy(alpha = 0.35f), color = MaterialTheme.colorScheme.onSurface)
  LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
    if (notes.isEmpty()) item {
      Text("No notes", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 24.dp))
    }
    for ((name, list) in shown) {
      if (name.isNotEmpty()) item(key = "h-$name") { SectionLabel(name) }
      groupItems(list, key = { "n-${it.guid}" }, inset = 16) { n ->
          Row(Modifier.fillMaxWidth().clickable { open(n) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(highlighted(n.title.ifBlank { "New Note" }, query, mark), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Row {
                Text(changed(n.modifiedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                  if (n.locked) AnnotatedString("Locked") else highlighted(n.snippet?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "No additional text", query, mark),
                  style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
              }
            }
            pictures[n.guid]?.let { NoteThumb(engine, it, Modifier.padding(start = 12.dp)) }
          }
      }
    }
  }
}

/**
 * A note open: the desktop's own editor in its page (NoteWebEditor), read and edited on one
 * surface, with the app's top bar around it. The page keeps the note's text and its save
 * session; the top bar asks it to save before it pins, moves, deletes or leaves, and reads the
 * note only for what the bar shows: whether it is locked, pinned, or in Recently Deleted.
 */
@Composable
private fun NoteScreen(engine: AccountInterface, opened: String?, folderGuid: String?, folders: List<NFolder>, onBack: () -> Unit, onProblem: (String) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var moving by remember { mutableStateOf(false) }
  var doc by remember { mutableStateOf<NDoc?>(null) }
  var reading by remember { mutableStateOf(opened != null) }
  // The new note this screen made, kept through a rotation: the text typed is with it, so the
  // screen comes back to it rather than to another empty new note.
  var created by rememberSaveable { mutableStateOf<String?>(null) }
  val openedAs = remember { opened ?: created }
  // The page owns the note; this holds what the top bar shows and the calls it makes to it.
  val editor = remember { NoteEditorState() }
  val guid by editor.guid.collectAsState()

  fun reason(t: Throwable) = (t as? uniffi.quince_mobile.EngineException.Failed)?.reason ?: "This note couldn't be saved."

  /** The note as the library holds it now: what the top bar shows, never what is typed. */
  suspend fun reread() {
    val g = editor.guid.value ?: return
    doc = withContext(Dispatchers.IO) { runCatching { engine.note(g) }.getOrNull() } ?: doc
  }

  // What is typed is saved before anything else touches the note: the page is asked and answers.
  fun pin(g: String) = scope.launch {
    editor.flush()
    runCatching { withContext(Dispatchers.IO) { engine.notePin(g, !(doc?.pinned ?: false)) } }.onFailure { onProblem(reason(it)) }
    syncNotes(context)
    reread()
  }
  fun move(g: String, to: String?) = scope.launch {
    editor.flush()
    runCatching { withContext(Dispatchers.IO) { engine.noteMove(g, to) } }.onFailure { onProblem(reason(it)) }
    syncNotes(context)
    reread()
  }
  fun remove(g: String) = scope.launch {
    editor.flush()
    runCatching { withContext(Dispatchers.IO) { engine.noteDelete(g) } }
      .onSuccess { syncNotes(context); onBack() }
      .onFailure { onProblem(reason(it)) }
  }

  LaunchedEffect(opened) {
    if (opened == null) {
      reading = false
      return@LaunchedEffect
    }
    runCatching { withContext(Dispatchers.IO) { engine.note(opened) } }
      .onSuccess { doc = it }
      .onFailure { onProblem(reason(it)) }
    reading = false
  }
  SideEffect {
    // A save, a new note or a file added landed: the list behind is read again.
    editor.onSaved = { guid ->
      if (opened == null) created = guid
      syncNotes(context)
    }
    editor.onError = onProblem
  }
  DisposableEffect(editor) {
    // A sync landed: the page reads the note again, under its own rules (never over typing).
    val stop = Outcome.watch(context) { editor.refresh() }
    onDispose { stop() }
  }
  // Back leaves the note as the desktop's does: what is typed is saved first, then the list.
  BackHandler { scope.launch { editor.flush(); onBack() } }

  if (reading) return

  val trashed = (doc?.folderGuid ?: folderGuid) == TRASH
  val locked = doc?.locked == true
  val g = guid
  val accent = MaterialTheme.colorScheme.primary
  Column(Modifier.fillMaxSize()) {
    // The desktop's note toolbar as phone actions: back; pin, move, delete (recover in Recently
    // Deleted). Each a mark in Notes' colour, named for TalkBack.
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { scope.launch { editor.flush(); onBack() } }) {
        Icon(painterResource(R.drawable.ic_back), contentDescription = "Back", tint = accent)
      }
      Spacer(Modifier.weight(1f))
      if (!locked && g != null) {
        if (trashed) {
          TextButton(onClick = { move(g, null) }) { Text("Recover") }
        } else {
          IconButton(onClick = { pin(g) }) {
            Icon(
              painterResource(R.drawable.ic_pin),
              contentDescription = if (doc?.pinned == true) "Unpin" else "Pin",
              tint = if (doc?.pinned == true) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = { moving = true }) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = "Move to Folder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (!locked && g != null) {
        if (trashed) {
          TextButton(onClick = { remove(g) }) { Text("Delete Now", color = MaterialTheme.colorScheme.error) }
        } else {
          IconButton(onClick = { remove(g) }) {
            Icon(painterResource(R.drawable.ic_trash), contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }
    // When it last changed, small and centred above the note, as the desktop shows it.
    doc?.modifiedAt?.let { at ->
      Text(
        Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center,
      )
    }
    NoteWebEditor(
      engine = engine,
      state = editor,
      guid = openedAs,
      folderGuid = folderGuid,
      editable = (doc?.editable ?: true) && !trashed,
      locked = locked,
      found = emptyList(),
      modifier = Modifier.fillMaxSize(),
    )
  }
  if (moving) {
    val g = guid
    AlertDialog(
      onDismissRequest = { moving = false },
      title = { Text("Move to Folder") },
      text = {
        Column {
          // Notes' own folder (no folder) first, then the person's; Recently Deleted is not a place to move to.
          val choices = listOf<Pair<String?, String>>(null to "Notes") + folders.filter { !it.trash }.map { it.guid to it.title }
          for ((to, title) in choices) {
            Row(
              Modifier.fillMaxWidth().clickable {
                moving = false
                if (g != null && to != doc?.folderGuid) move(g, to)
              }.padding(vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(14.dp))
              Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
              if (to == doc?.folderGuid) Icon(painterResource(R.drawable.ic_check), contentDescription = "Here now", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } },
    )
  }
}
