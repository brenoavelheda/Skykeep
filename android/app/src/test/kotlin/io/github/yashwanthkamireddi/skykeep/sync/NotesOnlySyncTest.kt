package io.github.yashwanthkamireddi.skykeep.sync

import android.provider.CalendarContract
import android.provider.ContactsContract
import io.github.yashwanthkamireddi.skykeep.BuildConfig
import io.github.yashwanthkamireddi.skykeep.ui.AUTHORITIES
import io.github.yashwanthkamireddi.skykeep.ui.NEEDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two flavors from the JVM: uNotes cloud's sync scope is Notes alone and its Setup asks for
 * nothing, while the suite keeps every service. Without the NOTES_ONLY branches the unotes build
 * fails here rather than on a phone, where it would create an account Android cannot sync.
 */
class NotesOnlySyncTest {
  /** Every authority the suite's account syncs, in the order SyncState lists them. */
  private val suite = listOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY, REMINDERS_AUTHORITY, NOTES_AUTHORITY, PHOTOS_AUTHORITY)

  @Test
  fun theFlagNamesTheFlavor() {
    assertEquals(
      if (BuildConfig.NOTES_ONLY) "io.github.brenoavelheda.unotes" else "io.github.yashwanthkamireddi.skykeep",
      BuildConfig.APPLICATION_ID,
    )
  }

  /** The Kotlin values and the generated resources follow the id the variant installs under. */
  @Test
  fun theAccountAndAuthoritiesAreThoseOfTheInstalledId() {
    assertEquals("${BuildConfig.APPLICATION_ID}.account", ACCOUNT_TYPE)
    for (authority in listOf(REMINDERS_AUTHORITY, NOTES_AUTHORITY, PHOTOS_AUTHORITY)) {
      assertTrue("$authority is under ${BuildConfig.APPLICATION_ID}", authority.startsWith("${BuildConfig.APPLICATION_ID}."))
    }
  }

  @Test
  fun theNotesOnlyAppSyncsNotesAndNothingElse() {
    if (BuildConfig.NOTES_ONLY) {
      assertEquals(listOf(NOTES_AUTHORITY), SYNCED)
      assertEquals(listOf(NOTES_AUTHORITY), AUTHORITIES)
      assertTrue("no phone permission is asked for", NEEDED.isEmpty())
    } else {
      assertEquals(suite, SYNCED)
      assertEquals(suite, AUTHORITIES)
      assertFalse("the suite still asks for Contacts and Calendar", NEEDED.isEmpty())
      assertEquals(4, NEEDED.size)
    }
  }
}
