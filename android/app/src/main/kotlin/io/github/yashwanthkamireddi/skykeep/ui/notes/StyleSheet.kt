package io.github.yashwanthkamireddi.skykeep.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.yashwanthkamireddi.skykeep.R
import androidx.compose.foundation.isSystemInDarkTheme

/** Notes' style number for Body, which a line may also carry as no style at all. */
private const val BODY = 3

/** One of Notes' paragraph styles: the number Apple stores (null for body), its name, its list mark. */
internal data class NoteStyle(val style: Int?, val label: String, val mark: String? = null)

/** The styles, in icloud.com's order, as the desktop's Aa panel lists them (FormatPanel.tsx). */
internal val NOTE_STYLES = listOf(
  NoteStyle(0, "Title"),
  NoteStyle(1, "Heading"),
  NoteStyle(2, "Subheading"),
  NoteStyle(null, "Body"),
  NoteStyle(4, "Monospaced"),
  NoteStyle(100, "Bulleted List", "•"),
  NoteStyle(101, "Dashed List", "-"),
  NoteStyle(102, "Numbered List", "1."),
)

/** Whether `style` is the line's own: Body for a line with no style or Apple's body number. */
internal fun isCurrentStyle(current: Int?, style: Int?): Boolean =
  if (style == null) current == null || current == BODY else current == style

/** One of Notes' text colours: the number Apple stores, its name, and how it is drawn in each theme. */
internal data class NoteColour(val colour: Int, val label: String, val light: Long, val dark: Long)

/** Notes' text colours in the phone's order, drawn as the desktop draws them (tokens.css, --note-c1 to 5). */
internal val NOTE_COLOURS = listOf(
  NoteColour(1, "Purple", 0xFFAF52DE, 0xFFBF5AF2),
  NoteColour(2, "Pink", 0xFFFF2D55, 0xFFFF375F),
  NoteColour(3, "Orange", 0xFFFF9500, 0xFFFF9F0A),
  NoteColour(4, "Mint", 0xFF00C7BE, 0xFF63E6E2),
  NoteColour(5, "Blue", 0xFF007AFF, 0xFF0A84FF),
)

/**
 * Notes' Aa sheet, opened from the format bar and sitting on it above the keyboard: the
 * paragraph styles, each drawn the way it sets a line and the current one ticked, then the text
 * colours and Block Quote. Nothing here takes the focus, so the keyboard stays up and typing
 * goes on in the chosen style, as on the iPhone; the sheet stays until Aa is tapped again.
 */
@Composable
fun StyleSheet(status: EditorStatus, onCommand: (name: String, arg: Any?) -> Unit, modifier: Modifier = Modifier) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(vertical = 6.dp)) {
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (s in NOTE_STYLES) StyleChip(s, isCurrentStyle(status.style, s.style)) { onCommand("style", s.style) }
      }
      Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
          Swatch(null, "No colour", status.colour == null) { onCommand("colour", null) }
          for (c in NOTE_COLOURS) Swatch(c, c.label, status.colour == c.colour) { onCommand("colour", c.colour) }
        }
        BarButton("Block Quote", on = status.quote, onClick = { onCommand("quote", null) }) {
          Icon(painterResource(R.drawable.ic_line_quote), contentDescription = null, modifier = Modifier.size(22.dp))
        }
      }
    }
  }
}

/** One style, named in its own look; the line's current one ticked and filled. */
@Composable
private fun StyleChip(style: NoteStyle, current: Boolean, onClick: () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  val type = MaterialTheme.typography
  val look: TextStyle = when (style.style) {
    0 -> type.titleLarge.copy(fontWeight = FontWeight.Bold)
    1 -> type.titleMedium.copy(fontWeight = FontWeight.Bold)
    2 -> type.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    4 -> type.bodyLarge.copy(fontFamily = FontFamily.Monospace)
    else -> type.bodyLarge
  }
  val ink = if (current) scheme.onPrimaryContainer else scheme.onSurface
  Row(
    Modifier
      .heightIn(min = 48.dp)
      .focusProperties { canFocus = false }
      .clip(RoundedCornerShape(12.dp))
      .background(if (current) scheme.primaryContainer else scheme.surfaceContainerHighest)
      .selectable(selected = current, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (current) {
      Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(6.dp))
    }
    style.mark?.let {
      Text(it, style = look, color = ink)
      Spacer(Modifier.width(6.dp))
    }
    Text(style.label, style = look, color = ink, maxLines = 1)
  }
}

/** One colour as a dot (none: an empty ring struck through), the chosen one ringed. */
@Composable
private fun Swatch(colour: NoteColour?, label: String, chosen: Boolean, onClick: () -> Unit) {
  val dark = isSystemInDarkTheme()
  val ring = MaterialTheme.colorScheme.onSurface
  val outline = MaterialTheme.colorScheme.outline
  Box(
    Modifier
      .size(48.dp)
      .focusProperties { canFocus = false }
      .clip(CircleShape)
      .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.size(36.dp).border(2.dp, if (chosen) ring else Color.Transparent, CircleShape).padding(4.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (colour != null) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(if (dark) colour.dark else colour.light)))
      } else {
        Box(
          Modifier.size(28.dp).border(1.5.dp, outline, CircleShape).drawBehind {
            val inset = size.width * 0.22f
            drawLine(outline, Offset(size.width - inset, inset), Offset(inset, size.height - inset), strokeWidth = 1.5.dp.toPx())
          },
        )
      }
    }
  }
}
