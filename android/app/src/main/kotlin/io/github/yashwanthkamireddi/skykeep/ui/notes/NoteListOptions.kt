package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.content.Context
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import uniffi.quince_mobile.NItem

/** How the list is ordered, as Notes' View Options offer it; `key` is how the choice is kept. */
enum class NoteSort(val key: String, val label: String) {
  Edited("edited", "Date Edited"),
  Created("created", "Date Created"),
  Title("title", "Title"),
}

/** The list's order: by what, and whether in date groups (never by title, as in Notes). */
data class NoteOrder(val by: NoteSort = NoteSort.Edited, val groupByDate: Boolean = true) {
  companion object {
    /** The order kept as its parts; Date Edited for anything not one of the choices. */
    fun parse(by: String?, groupByDate: Boolean) = NoteOrder(NoteSort.entries.firstOrNull { it.key == by } ?: NoteSort.Edited, groupByDate)
  }
}

/** One run of the list under its name; the name empty for a run shown without one. */
data class NoteSection(val name: String, val notes: List<NItem>)

/** The choice kept on this phone, across launches, beside the app's other settings. */
object NoteListPrefs {
  private const val SORT = "notesSort"
  private const val GROUP = "notesGroupByDate"

  fun read(context: Context): NoteOrder {
    val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    return NoteOrder.parse(p.getString(SORT, null), p.getBoolean(GROUP, true))
  }

  fun write(context: Context, order: NoteOrder) {
    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
      .putString(SORT, order.by.key).putBoolean(GROUP, order.groupByDate).apply()
  }
}

/** Titles A to Z as a person reads them: case and accents aside. */
private val byTitle: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

private fun titleOf(n: NItem) = n.title.trim()

/** The date the order goes by: when the note was made, or when it last changed. */
private fun dateOf(n: NItem, by: NoteSort) = if (by == NoteSort.Created) n.createdAt else n.modifiedAt

/**
 * Notes in the list's order, as the desktop orders them: pinned first, then newest first by the
 * chosen date (ties by date edited, then title), or by title A to Z (ties by date edited). A
 * note with no date goes after every one that has one.
 */
fun sorted(notes: List<NItem>, order: NoteOrder): List<NItem> {
  val newest = compareByDescending<NItem, Long?>(nullsFirst()) { it.modifiedAt }
  val within: Comparator<NItem> = when (order.by) {
    NoteSort.Title -> Comparator<NItem> { a, b -> byTitle.compare(titleOf(a), titleOf(b)) }.then(newest)
    NoteSort.Created -> compareByDescending<NItem, Long?>(nullsFirst()) { it.createdAt }.then(newest)
      .then { a, b -> byTitle.compare(titleOf(a), titleOf(b)) }
    NoteSort.Edited -> newest.then { a, b -> byTitle.compare(titleOf(a), titleOf(b)) }
  }
  return notes.sortedWith(compareByDescending<NItem> { it.pinned }.then(within))
}

/** Which date group a note's date puts it in, as Notes names them. */
private fun bucket(at: Long?, today: LocalDate, zone: ZoneId, locale: Locale): String {
  val d = at?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() } ?: return "Earlier"
  return when {
    d == today -> "Today"
    d == today.minusDays(1) -> "Yesterday"
    d.isAfter(today.minusDays(7)) -> "Previous 7 Days"
    d.isAfter(today.minusDays(30)) -> "Previous 30 Days"
    d.year == today.year -> d.month.getDisplayName(TextStyle.FULL, locale)
    else -> d.year.toString()
  }
}

/**
 * The list in its sections: Pinned first, then, grouped by date, Today, Yesterday, Previous 7
 * Days, Previous 30 Days, each earlier month of this year, each earlier year, and Earlier for
 * notes with no date. Not grouped (or by title): one run, named Notes only when Pinned is
 * shown above it.
 */
fun sections(
  notes: List<NItem>,
  order: NoteOrder,
  today: LocalDate = LocalDate.now(),
  zone: ZoneId = ZoneId.systemDefault(),
  locale: Locale = Locale.getDefault(),
): List<NoteSection> {
  val (pinned, rest) = sorted(notes, order).partition { it.pinned }
  val dated = order.groupByDate && order.by != NoteSort.Title
  val runs = if (dated) {
    // Notes went in sorted, so the groups come out in order, with Earlier (no date) last.
    rest.groupBy { bucket(dateOf(it, order.by), today, zone, locale) }.map { (name, list) -> NoteSection(name, list) }
  } else {
    listOfNotNull(rest.takeIf { it.isNotEmpty() }?.let { NoteSection(if (pinned.isEmpty()) "" else "Notes", it) })
  }
  return listOfNotNull(pinned.takeIf { it.isNotEmpty() }?.let { NoteSection("Pinned", it) }) + runs
}
