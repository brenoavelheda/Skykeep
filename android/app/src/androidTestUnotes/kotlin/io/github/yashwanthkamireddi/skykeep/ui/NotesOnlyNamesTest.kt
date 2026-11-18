package io.github.yashwanthkamireddi.skykeep.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yashwanthkamireddi.skykeep.ui.theme.SkykeepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What uNotes cloud calls itself before and around the notes: the welcome speaks of Notes alone
 * in the app's own name, keeps "not affiliated with Apple", and the licences still say the app
 * is built from Skykeep, as the GPL-3.0 asks. Neither screen reads anything of the person's.
 */
@RunWith(AndroidJUnit4::class)
class NotesOnlyNamesTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun theWelcomeIsForNotesInTheAppsName() {
    var continued = 0
    compose.setContent { SkykeepTheme { NotesOnlyTheme(true) { Welcome { continued++ } } } }
    compose.onNodeWithContentDescription("uNotes cloud").assertIsDisplayed()
    compose.onNodeWithText("Your iCloud notes, on Android").assertIsDisplayed()
    compose.onNodeWithText("Talks only to Apple. No uNotes cloud account, no ads, no tracking.").assertIsDisplayed()
    compose.onNodeWithText("Not made by or affiliated with Apple", substring = true).assertIsDisplayed()
    // The suite's promises (Reminders, Photos, Drive, contacts and calendars) are not this app's.
    for (word in listOf("Skykeep", "Reminders", "Photos", "Drive", "contacts", "calendars")) {
      compose.onAllNodesWithText(word, substring = true).assertCountEquals(0)
    }
    compose.onNodeWithText("Get Started").performClick()
    assertEquals(1, continued)
  }

  @Test
  fun theLicencesSayTheAppIsBuiltFromSkykeep() {
    compose.setContent { SkykeepTheme { NotesOnlyTheme(true) { LicencesPage {} } } }
    compose.onNodeWithText(
      "uNotes cloud is built from Skykeep and, like it, is free software under the GPL-3.0. Both are built on the work below, each under its own licence.",
    ).assertIsDisplayed()
  }
}
