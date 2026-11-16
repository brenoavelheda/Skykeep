package io.github.yashwanthkamireddi.skykeep.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yashwanthkamireddi.skykeep.R
import kotlin.math.roundToInt
import io.github.yashwanthkamireddi.skykeep.ui.FullScreen
import uniffi.quince_mobile.AccountInterface

/**
 * The note open in its page with Notes' format bar on the keyboard: the bar shows while the note
 * can be typed in and is being typed in (the keyboard up, or the editor holding the focus), and
 * Aa opens the styles sheet on top of it. The app's tab bar steps aside while the keyboard is up,
 * and the bar sits right on the keyboard with the page shrunk above both.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditingArea(
  engine: AccountInterface,
  state: NoteEditorState,
  guid: String?,
  folderGuid: String?,
  editable: Boolean,
  locked: Boolean,
  found: List<String>,
  modifier: Modifier = Modifier,
) {
  val status by state.status.collectAsState()
  val keyboard = WindowInsets.isImeVisible
  var focused by remember { mutableStateOf(false) }
  var styles by remember { mutableStateOf(false) }
  val bar = editable && !locked && (keyboard || focused)
  LaunchedEffect(bar) { if (!bar) styles = false }
  if (keyboard) FullScreen()

  // What the keyboard covers of this area is left free, so the bar and the page sit above it:
  // the keyboard's height less whatever already lies between this area and the window's bottom
  // (the system's navigation bar, the app's tab bar while it shows). The page's own padding for
  // the keyboard then has nothing left to add.
  val density = LocalDensity.current
  val window = LocalView.current.rootView
  val ime = WindowInsets.ime.getBottom(density)
  var below by remember { mutableIntStateOf(0) }
  val covered = with(density) { (ime - below).coerceAtLeast(0).toDp() }
  Column(
    modifier
      .onGloballyPositioned { c ->
        below = (window.height - c.boundsInWindow().bottom).roundToInt().coerceAtLeast(0)
      }
      .padding(bottom = covered)
      .consumeWindowInsets(WindowInsets.ime),
  ) {
    Box(Modifier.weight(1f).fillMaxWidth().onFocusChanged { focused = it.hasFocus }) {
      NoteWebEditor(engine, state, guid, folderGuid, editable, locked, found, Modifier.fillMaxSize())
    }
    if (bar) {
      if (styles) StyleSheet(status, state::command)
      FormatBar(status, stylesOpen = styles, onStyles = { styles = !styles }, onCommand = state::command)
    }
  }
}

/**
 * Notes' format bar, as the iPhone's keyboard bar and the desktop's Aa panel offer it: Aa (the
 * styles sheet), checklist, bold, italic, underline, strikethrough, link; indent, outdent, move
 * the line up or down; undo, redo. Each lit from what the page reports of the caret, each 48 dp
 * and named for TalkBack; one row, scrolled sideways where the screen is narrow. No button takes
 * the focus: the editor keeps it, and with it the keyboard.
 */
@Composable
fun FormatBar(
  status: EditorStatus,
  stylesOpen: Boolean,
  onStyles: () -> Unit,
  onCommand: (name: String, arg: Any?) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().testTag("format-bar")) {
    Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BarButton("Text Styles", on = stylesOpen, onClick = onStyles) { Glyph("Aa") }
        BarButton("Checklist", on = status.checklist, onClick = { onCommand("checklist", null) }) { Mark(R.drawable.ic_checklist) }
        BarButton("Bold", on = status.bold, onClick = { onCommand("bold", null) }) { Glyph("B", weight = FontWeight.Bold) }
        BarButton("Italic", on = status.italic, onClick = { onCommand("italic", null) }) { Glyph("I", style = FontStyle.Italic) }
        BarButton("Underline", on = status.underline, onClick = { onCommand("underline", null) }) { Glyph("U", decoration = TextDecoration.Underline) }
        BarButton("Strikethrough", on = status.strike, onClick = { onCommand("strike", null) }) { Glyph("S", decoration = TextDecoration.LineThrough) }
        BarButton(if (status.link) "Edit Link" else "Add Link", on = status.link, onClick = { onCommand("link", null) }) { Mark(R.drawable.ic_link) }
        Gap()
        BarButton("Decrease Indent", onClick = { onCommand("outdent", null) }) { Mark(R.drawable.ic_line_outdent) }
        BarButton("Increase Indent", onClick = { onCommand("indent", null) }) { Mark(R.drawable.ic_line_indent) }
        BarButton("Move Line Up", onClick = { onCommand("moveUp", null) }) { Mark(R.drawable.ic_line_up) }
        BarButton("Move Line Down", onClick = { onCommand("moveDown", null) }) { Mark(R.drawable.ic_line_down) }
        Gap()
        BarButton("Undo", enabled = status.canUndo, onClick = { onCommand("undo", null) }) { Mark(R.drawable.ic_text_undo) }
        BarButton("Redo", enabled = status.canRedo, onClick = { onCommand("redo", null) }) { Mark(R.drawable.ic_text_redo) }
      }
    }
  }
}

/**
 * One of the bar's buttons, 48 dp, never focusable (a tap leaves the editor's focus and the
 * keyboard where they are). `on` set: a toggle, filled in Notes' colour while on and announced
 * as checked; null: a plain button.
 */
@Composable
internal fun BarButton(
  label: String,
  on: Boolean? = null,
  enabled: Boolean = true,
  onClick: () -> Unit,
  content: @Composable () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val lit = on == true
  val ink = when {
    !enabled -> scheme.onSurface.copy(alpha = 0.38f)
    lit -> scheme.onPrimaryContainer
    else -> scheme.onSurface
  }
  val act = if (on != null) {
    Modifier.toggleable(value = on, enabled = enabled, role = Role.Checkbox) { onClick() }
  } else {
    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
  }
  Box(
    Modifier
      .size(48.dp)
      .focusProperties { canFocus = false }
      .padding(2.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(if (lit) scheme.primaryContainer else Color.Transparent)
      .then(act)
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    CompositionLocalProvider(LocalContentColor provides ink) { content() }
  }
}

/** A letter drawn as the format it stands for, as the desktop's panel draws B, I, U and S. */
@Composable
private fun Glyph(text: String, weight: FontWeight = FontWeight.Medium, style: FontStyle = FontStyle.Normal, decoration: TextDecoration? = null) {
  Text(text, color = LocalContentColor.current, fontSize = 19.sp, fontWeight = weight, fontStyle = style, textDecoration = decoration)
}

@Composable
private fun Mark(icon: Int) {
  Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
}

/** The bar's groups apart: marks, lines, history. */
@Composable
private fun Gap() {
  VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp).width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
