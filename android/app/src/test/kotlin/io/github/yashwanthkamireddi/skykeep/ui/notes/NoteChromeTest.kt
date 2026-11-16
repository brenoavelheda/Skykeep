package io.github.yashwanthkamireddi.skykeep.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The note screen's chrome where it is plain logic: the styles sheet, export's name, the searched words. */
class NoteChromeTest {
  @Test
  fun the_styles_are_notes_own_in_icloud_order() {
    assertEquals(
      listOf("Title", "Heading", "Subheading", "Body", "Monospaced", "Bulleted List", "Dashed List", "Numbered List"),
      NOTE_STYLES.map { it.label },
    )
    assertEquals(listOf(0, 1, 2, null, 4, 100, 101, 102), NOTE_STYLES.map { it.style })
  }

  @Test
  fun body_is_ticked_for_no_style_and_for_style_three() {
    assertTrue(isCurrentStyle(current = null, style = null))
    assertTrue(isCurrentStyle(current = 3, style = null))
    assertFalse(isCurrentStyle(current = 0, style = null))
    assertTrue(isCurrentStyle(current = 101, style = 101))
    assertFalse(isCurrentStyle(current = null, style = 0))
    // A checklist line is none of the sheet's styles: nothing is ticked.
    assertTrue(NOTE_STYLES.none { isCurrentStyle(103, it.style) })
  }

  @Test
  fun the_colours_are_the_ones_apple_stores() {
    assertEquals(listOf(1, 2, 3, 4, 5), NOTE_COLOURS.map { it.colour })
    assertEquals(listOf("Purple", "Pink", "Orange", "Mint", "Blue"), NOTE_COLOURS.map { it.label })
  }

  @Test
  fun export_names_the_file_after_the_first_line() {
    assertEquals("Lemon tart.md", markdownFileName("  Lemon tart \nflour\n"))
    assertEquals("Note.md", markdownFileName("\nsecond line"))
    assertEquals("Note.md", markdownFileName(""))
    assertEquals("a-b-c-d.md", markdownFileName("a/b:c?d"))
  }

  @Test
  fun export_names_are_cut_at_eighty_without_splitting_an_emoji() {
    val name = markdownFileName("x".repeat(79) + "😀 and more")
    // 79 letters, then the emoji would cross 80: it is left out whole rather than cut in half.
    assertEquals("x".repeat(79) + ".md", name)
    assertEquals(80 + 3, markdownFileName("y".repeat(200)).length)
  }

  @Test
  fun the_searched_words_are_the_query_split_on_spaces() {
    assertEquals(listOf("lemon", "tart"), foundWords("  lemon   tart "))
    assertEquals(emptyList<String>(), foundWords("   "))
  }
}
