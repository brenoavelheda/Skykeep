package io.github.yashwanthkamireddi.skykeep.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightTest {
  @Test
  fun every_searched_word_is_marked_in_any_order() {
    assertEquals(listOf(0 until 5, 17 until 21), highlights("Lunch notes from Rome", "rome lunch"))
  }

  @Test
  fun a_word_is_marked_where_it_begins_a_word_only() {
    assertEquals(listOf(6 until 9, 16 until 19), highlights("Rome, cathedral cat", "cat"))
    assertEquals(emptyList<IntRange>(), highlights("concatenate", "cat"))
  }

  @Test
  fun overlapping_matches_are_merged_into_one_mark() {
    // "s" and "spring" both begin "Springfield": one mark, the longer.
    assertEquals(listOf(0 until 6), highlights("Springfield", "s spring"))
    assertEquals(listOf(0 until 6), highlights("Springfield", "spring s"))
  }

  @Test
  fun case_and_accents_aside_as_the_search_finds_them() {
    assertEquals(listOf(7 until 11), highlights("Lunch, Café Rouge", "cafe"))
    assertEquals(listOf(0 until 4), highlights("CAFE", "café"))
    // The same word written decomposed: e and its accent, still one word marked whole.
    assertEquals(listOf(0 until 5), highlights("Café", "cafe"))
  }

  @Test
  fun an_emoji_before_the_match_does_not_shift_it() {
    val text = "😀 Café"
    assertEquals(listOf(3 until 7), highlights(text, "cafe"))
    assertEquals("Café", text.substring(3, 7))
  }

  @Test
  fun nothing_to_look_for_marks_nothing() {
    assertEquals(emptyList<IntRange>(), highlights("Anything", ""))
    assertEquals(emptyList<IntRange>(), highlights("Anything", "  - & "))
    assertEquals(emptyList<IntRange>(), highlights("", "cafe"))
  }

  @Test
  fun punctuation_in_a_typed_word_splits_it_as_the_search_does() {
    assertEquals(listOf(0 until 3, 4 until 5), highlights("don t", "don't"))
  }

  @Test
  fun the_drawn_text_carries_a_style_on_each_mark() {
    val style = SpanStyle(background = Color.Yellow)
    val drawn = highlighted("Lunch at Café", "cafe", style)
    assertEquals("Lunch at Café", drawn.text)
    assertEquals(listOf(9 to 13), drawn.spanStyles.map { it.start to it.end })
    assertEquals(style, drawn.spanStyles.single().item)
  }
}
