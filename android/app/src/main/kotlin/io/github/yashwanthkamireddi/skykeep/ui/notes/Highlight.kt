package io.github.yashwanthkamireddi.skykeep.ui.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.Normalizer

// The searched words found in a row's title and preview, by the rules the engine's search
// finds a note by (quince-core's `search.rs` and `fold.rs`): every typed word has to begin a
// word of the note, case and accents aside, so "cafe" lights "Café" and never the middle of
// "concatenate". Ranges are in UTF-16 units, as a Kotlin string counts.

/** How many typed words the search looks for (quince-core's `MOST_WORDS`). */
private const val MOST_WORDS = 12

/** Accents that sit on a letter without changing which letter it is (quince-core's `is_accent`). */
private fun isAccent(c: Int) =
  c in 0x0300..0x036F || c in 0x1AB0..0x1AFF || c in 0x1DC0..0x1DFF || c in 0x20D0..0x20FF || c in 0xFE20..0xFE2F

/** `text` with case and accents gone, so "Émile", "EMILE" and "emile" are one word. */
private fun fold(text: String): String = buildString {
  Normalizer.normalize(text, Normalizer.Form.NFD).codePoints().forEach { c ->
    // Lower-cased a character at a time, without regard to locale, as Rust's `to_lowercase`.
    if (!isAccent(c)) append(String(Character.toChars(c)).lowercase())
  }
}

/** A letter or a digit of any script, as Rust's `char::is_alphanumeric`. */
private fun alphanumeric(c: Int): Boolean =
  Character.isAlphabetic(c) || when (Character.getType(c).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
  }

/** A character inside a word, as the search index's tokenizer has it (quince-core's `in_word`). */
private fun inWord(c: Int): Boolean =
  alphanumeric(c) || c in 0x0300..0x036F || c in 0x0483..0x0489 || c in 0x0591..0x05C7 || c in 0x0610..0x061A ||
    c in 0x064B..0x065F || c in 0x0900..0x0963 || c in 0x0966..0x0DFF || c in 0x1AB0..0x1AFF ||
    c in 0x1DC0..0x1DFF || c in 0x20D0..0x20FF || c in 0xFE20..0xFE2F

/** The words of `text`, each as its UTF-16 range. */
private fun wordsOf(text: String): List<IntRange> {
  val out = mutableListOf<IntRange>()
  var start = -1
  var i = 0
  while (i < text.length) {
    val c = text.codePointAt(i)
    if (inWord(c)) { if (start < 0) start = i } else if (start >= 0) { out += start until i; start = -1 }
    i += Character.charCount(c)
  }
  if (start >= 0) out += start until text.length
  return out
}

/** The typed words, folded and split as the index splits them, longest first. */
private fun foldedWords(query: String): List<String> =
  query.split(Regex("\\s+"))
    .filter { w -> w.codePoints().anyMatch(::alphanumeric) }
    .take(MOST_WORDS)
    .flatMap { w -> wordsOf(w).map { fold(w.substring(it.first, it.last + 1)) } }
    .filter { it.isNotEmpty() }
    // Longest first, so "spring" lights all of "Springfield" where "s" and "spring" both begin it.
    .sortedByDescending { it.length }

/**
 * How much of `word` (from its start) it takes for its folded form to reach `prefix`, in
 * UTF-16 units; null when the folded word does not begin with `prefix`. An accent written
 * apart after the last letter goes with it, so a mark never ends between a letter and its accent.
 */
private fun prefixEnd(word: String, prefix: String): Int? {
  val folded = StringBuilder()
  var i = 0
  while (i < word.length) {
    val c = word.codePointAt(i)
    val piece = fold(String(Character.toChars(c)))
    if (folded.length >= prefix.length && piece.isNotEmpty()) break
    folded.append(piece)
    i += Character.charCount(c)
  }
  return i.takeIf { folded.startsWith(prefix) }
}

/**
 * Where in `text` a word begins with one of the searched words of `query`, as UTF-16 ranges in
 * order: one mark per word of the text, the longest searched word it begins with, so matches
 * that overlap are one mark.
 */
fun highlights(text: String, query: String): List<IntRange> {
  val words = foldedWords(query)
  if (words.isEmpty() || text.isEmpty()) return emptyList()
  return wordsOf(text).mapNotNull { at ->
    val word = text.substring(at.first, at.last + 1)
    words.firstNotNullOfOrNull { prefixEnd(word, it) }?.takeIf { it > 0 }?.let { at.first until at.first + it }
  }
}

/** `text` with `style` on each place the searched words of `query` begin a word. */
fun highlighted(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
  append(text)
  for (r in highlights(text, query)) addStyle(style, r.first, r.last + 1)
}
