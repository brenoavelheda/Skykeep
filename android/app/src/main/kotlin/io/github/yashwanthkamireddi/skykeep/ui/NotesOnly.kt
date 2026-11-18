package io.github.yashwanthkamireddi.skykeep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.yashwanthkamireddi.skykeep.ui.theme.AppTheme
import io.github.yashwanthkamireddi.skykeep.ui.theme.Palette
import uniffi.quince_mobile.AccountInterface

/**
 * uNotes cloud once signed in: the notes, filling the screen. No bottom bar and no other module.
 * The Scaffold gives the page the system bars as MainScreens does for its Notes tab, and
 * [LocalFullScreen] is kept for the note page's own full-screen moments (the attachment viewer);
 * there is no bar here for it to hide.
 */
@Composable
fun NotesOnlyScreens(engine: AccountInterface, onSignedOut: () -> Unit) {
  val full = remember { mutableStateOf(false) }
  CompositionLocalProvider(LocalFullScreen provides full) {
    Scaffold { inner ->
      Box(Modifier.padding(inner).fillMaxSize()) {
        AppTheme(Palette.App.Notes) { NotesPage(engine) }
      }
    }
  }
}
