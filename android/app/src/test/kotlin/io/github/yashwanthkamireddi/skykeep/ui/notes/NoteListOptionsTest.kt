package io.github.yashwanthkamireddi.skykeep.ui.notes

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.quince_mobile.NItem

class NoteListOptionsTest {
  private val zone = ZoneOffset.UTC
  private val today = LocalDate.of(2026, 11, 16)
  private fun day(daysAgo: Long): Long = today.minusDays(daysAgo).atTime(12, 0).toEpochSecond(zone)

  private fun note(guid: String, title: String = guid, edited: Long? = null, created: Long? = null, pinned: Boolean = false) =
    NItem(guid = guid, folderGuid = null, title = title, snippet = null, createdAt = created, modifiedAt = edited, pinned = pinned, locked = false)

  private fun guids(notes: List<NItem>) = notes.map { it.guid }

  @Test
  fun by_date_edited_newest_first_with_pinned_first_and_ties_by_title() {
    val notes = listOf(
      note("b", edited = day(1)),
      note("old", edited = day(9)),
      note("a", edited = day(1)),
      note("pin", edited = day(30), pinned = true),
    )
    assertEquals(listOf("pin", "a", "b", "old"), guids(sorted(notes, NoteOrder(NoteSort.Edited))))
  }

  @Test
  fun by_date_created_newest_first_with_ties_by_date_edited() {
    val notes = listOf(
      note("x", created = day(5), edited = day(4)),
      note("y", created = day(5), edited = day(2)),
      note("new", created = day(1), edited = day(9)),
      note("pin", created = day(40), pinned = true),
    )
    assertEquals(listOf("pin", "new", "y", "x"), guids(sorted(notes, NoteOrder(NoteSort.Created))))
  }

  @Test
  fun by_title_a_to_z_case_and_accents_aside_with_ties_by_date_edited() {
    val notes = listOf(
      note("z", title = "zebra", edited = day(1)),
      note("e2", title = "Émile", edited = day(1)),
      note("e1", title = "emile", edited = day(3)),
      note("a", title = "Apple", edited = day(9)),
      note("pin", title = "Zoo", pinned = true),
    )
    assertEquals(listOf("pin", "a", "e2", "e1", "z"), guids(sorted(notes, NoteOrder(NoteSort.Title))))
  }

  @Test
  fun a_missing_date_sorts_last() {
    val notes = listOf(note("none", edited = day(0)), note("old", created = day(300), edited = day(300)))
    assertEquals(listOf("old", "none"), guids(sorted(notes, NoteOrder(NoteSort.Created))))
    val edited = listOf(note("none"), note("old", edited = day(300)))
    assertEquals(listOf("old", "none"), guids(sorted(edited, NoteOrder(NoteSort.Edited))))
  }

  @Test
  fun grouped_gives_the_date_buckets_pinned_first_and_earlier_last() {
    val notes = listOf(
      note("none"),
      note("today", edited = day(0)),
      note("yesterday", edited = day(1)),
      note("week", edited = day(3)),
      note("month", edited = day(20)),
      note("pin", edited = day(2), pinned = true),
    )
    val got = sections(notes, NoteOrder(NoteSort.Edited, groupByDate = true), today, zone)
    assertEquals(listOf("Pinned", "Today", "Yesterday", "Previous 7 Days", "Previous 30 Days", "Earlier"), got.map { it.name })
    assertEquals(listOf("pin"), guids(got[0].notes))
    assertEquals(listOf("none"), guids(got.last().notes))
  }

  @Test
  fun grouped_by_date_created_buckets_by_the_created_date() {
    val notes = listOf(note("n", created = day(0), edited = day(100)))
    assertEquals(listOf("Today"), sections(notes, NoteOrder(NoteSort.Created), today, zone).map { it.name })
  }

  @Test
  fun earlier_months_and_years_by_name() {
    val notes = listOf(
      note("march", edited = LocalDate.of(2026, 3, 4).atTime(9, 0).toEpochSecond(zone)),
      note("lastYear", edited = LocalDate.of(2024, 7, 1).atTime(9, 0).toEpochSecond(zone)),
    )
    assertEquals(listOf("March", "2024"), sections(notes, NoteOrder(NoteSort.Edited), today, zone, java.util.Locale.US).map { it.name })
  }

  @Test
  fun ungrouped_is_one_unnamed_section() {
    val notes = listOf(note("a", edited = day(0)), note("b", edited = day(40)))
    val got = sections(notes, NoteOrder(NoteSort.Edited, groupByDate = false), today, zone)
    assertEquals(listOf(""), got.map { it.name })
    assertEquals(listOf("a", "b"), guids(got[0].notes))
  }

  @Test
  fun ungrouped_keeps_pinned_apart_under_its_name() {
    val notes = listOf(note("a", edited = day(0)), note("pin", edited = day(40), pinned = true))
    val got = sections(notes, NoteOrder(NoteSort.Edited, groupByDate = false), today, zone)
    assertEquals(listOf("Pinned", "Notes"), got.map { it.name })
  }

  @Test
  fun by_title_is_never_grouped_by_date() {
    val notes = listOf(note("b", title = "B", edited = day(0)), note("a", title = "A", edited = day(40)))
    val got = sections(notes, NoteOrder(NoteSort.Title, groupByDate = true), today, zone)
    assertEquals(listOf(""), got.map { it.name })
    assertEquals(listOf("a", "b"), guids(got[0].notes))
  }

  @Test
  fun nothing_gives_no_sections() {
    assertEquals(emptyList<NoteSection>(), sections(emptyList(), NoteOrder(), today, zone))
  }

  @Test
  fun the_choice_round_trips_through_its_stored_names() {
    for (by in NoteSort.entries) for (grouped in listOf(true, false)) {
      val order = NoteOrder(by, grouped)
      assertEquals(order, NoteOrder.parse(order.by.key, order.groupByDate))
    }
    assertEquals(NoteOrder(), NoteOrder.parse(null, true))
    assertEquals(NoteOrder(), NoteOrder.parse("nonsense", true))
  }
}
