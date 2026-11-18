package io.github.yashwanthkamireddi.skykeep.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yashwanthkamireddi.skykeep.sync.NOTES_AUTHORITY
import io.github.yashwanthkamireddi.skykeep.sync.Outcome
import io.github.yashwanthkamireddi.skykeep.ui.theme.SkykeepTheme
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.quince_mobile.AccountInterface
import uniffi.quince_mobile.AccountProfile
import uniffi.quince_mobile.DataAccessNow
import uniffi.quince_mobile.DataState
import uniffi.quince_mobile.NFolder

/**
 * uNotes cloud's own screens with a fake `AccountInterface`: the folders as the first screen,
 * Settings and its way back, Sign Out's question, and the notices above the folders. Nothing
 * here signs in and nothing of the person's is read or changed: the engine is the fake, the
 * phone account is none, and the screens run on [Isolated], which keeps every preference the
 * screens read or write in this test's own files rather than the app's.
 */
@RunWith(AndroidJUnit4::class)
class NotesOnlyTest {
  @get:Rule
  val compose = createAndroidComposeRule<ComponentActivity>()

  /** The engine as these screens ask it: two folders, an account's name, and what was asked of it. */
  private class FakeEngine {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var standing: DataAccessNow? = null
    val engine: AccountInterface = Proxy.newProxyInstance(
      AccountInterface::class.java.classLoader,
      arrayOf(AccountInterface::class.java),
    ) { proxy, method, args ->
      // An unsigned type gives the JVM method a hash after its name: the export's name is before it.
      val name = method.name.substringBefore('-')
      calls += name
      when (name) {
        "equals" -> proxy === args?.get(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "FakeEngine"
        "noteFolders" -> listOf(
          NFolder("F-1", "Recipes", null, 3u, false),
          NFolder("TrashFolder-CloudKit", "Recently Deleted", null, 1u, true),
        )
        "appleId" -> "reader@example.com"
        "accountProfile" -> AccountProfile("Test Reader", "reader@example.com", null)
        "accountTag" -> "test-account"
        "dataAccessStanding" -> standing ?: DataAccessNow(DataState.OPEN, emptyList(), null)
        else -> when {
          method.returnType == Void.TYPE -> Unit
          method.returnType == java.lang.Boolean.TYPE -> false
          method.returnType == java.lang.Integer.TYPE -> 0
          method.returnType == java.lang.Long.TYPE -> 0L
          method.returnType == String::class.java -> "{}"
          List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
          else -> null
        }
      }
    } as AccountInterface
  }

  /**
   * The test's context: the app's own, but every preference file is a test one, so the notes'
   * view options, the sync records and the phone account's name are the test's, never the
   * app's. The phone account named here is one no account on the phone has.
   */
  private class Isolated(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
      baseContext.getSharedPreferences("notes-only-test-$name", mode)
    override fun deleteSharedPreferences(name: String): Boolean = baseContext.deleteSharedPreferences("notes-only-test-$name")
  }

  private val fake = FakeEngine()
  private val signIns = CopyOnWriteArrayList<String>()
  private val signedOut = CopyOnWriteArrayList<String>()
  private val synced = CopyOnWriteArrayList<String>()
  private lateinit var isolated: Isolated

  private val now = System.currentTimeMillis()
  private val upToDate = SyncView(
    busy = false, held = 0,
    last = mapOf(NOTES_AUTHORITY to Outcome.Last(at = now - 60_000, ok = true, said = "4 notes", okAt = now - 60_000)),
  )

  private var sessionEnded by mutableStateOf(false)
  private var sync by mutableStateOf(upToDate)

  private fun show() {
    isolated = Isolated(compose.activity)
    isolated.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("phoneAccount", "no account here").commit()
    compose.setContent {
      CompositionLocalProvider(LocalContext provides isolated) {
        SkykeepTheme {
          NotesOnlyTheme(true) {
            NotesOnly(
              engine = fake.engine, account = null, sync = sync, sessionEnded = sessionEnded, online = true,
              onSync = { synced += "sync" }, onSignIn = { signIns += "sign in" }, onSignedOut = { signedOut += "out" },
            )
          }
        }
      }
    }
    compose.waitForIdle()
  }

  @After
  fun forget() {
    if (::isolated.isInitialized) for (name in listOf("settings", "sync-outcome")) isolated.deleteSharedPreferences(name)
  }

  private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

  @Test
  fun theFirstScreenIsTheFoldersWithNoBottomBar() {
    show()
    compose.onNodeWithText("Notes").assertIsDisplayed()
    compose.onNodeWithText("All iCloud").assertIsDisplayed()
    compose.onNodeWithText("Recipes").assertIsDisplayed()
    compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    // The suite's bottom bar names every module; here there is none.
    for (tab in listOf("Home", "Reminders", "Photos", "Drive")) compose.onAllNodesWithText(tab).assertCountEquals(0)
    // Back on the folders is the system's: it leaves the app.
    assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
  }

  @Test
  fun settingsOpensFromTheFoldersAndBackComesUpOneLevel() {
    show()
    compose.onNodeWithContentDescription("Settings").performClick()
    compose.onNodeWithText("Settings").assertIsDisplayed()
    compose.onNodeWithText("Test Reader").assertIsDisplayed()
    compose.onNodeWithText("Sync Now").assertIsDisplayed()
    compose.onNodeWithText("Open-source licences").performScrollTo().performClick()
    compose.onAllNodesWithText("Sign Out").assertCountEquals(0)
    compose.onNodeWithText("Open-source licences").assertIsDisplayed()
    back()
    compose.onNodeWithText("Sign Out").performScrollTo().assertIsDisplayed()
    back()
    compose.onNodeWithText("All iCloud").assertIsDisplayed()
    compose.onAllNodesWithText("Sign Out").assertCountEquals(0)
    // The page's own back button goes the same way.
    compose.onNodeWithContentDescription("Settings").performClick()
    compose.onNodeWithContentDescription("Notes").performClick()
    compose.onNodeWithText("All iCloud").assertIsDisplayed()
  }

  @Test
  fun signOutAsksFirstThenSignsOutOfTheEngine() {
    show()
    compose.onNodeWithContentDescription("Settings").performClick()
    compose.onNodeWithText("Sign Out").performScrollTo().performClick()
    compose.onNodeWithText("Sign out of iCloud on this phone?").assertIsDisplayed()
    compose.onNodeWithText("your notes stay in uNotes cloud", substring = true).assertIsDisplayed()
    assertFalse("signed out before the person chose", "signOut" in fake.calls)
    // Keep a Copy: with no phone account and the library kept, nothing but the fake is touched.
    compose.onNodeWithText("Keep a Copy").performClick()
    compose.waitUntil(5_000) { signedOut.isNotEmpty() }
    assertTrue("signOut" in fake.calls)
    assertFalse("the library was removed", "forgetAccount" in fake.calls)
    assertEquals(listOf("out"), signedOut.toList())
  }

  @Test
  fun cancellingSignOutSignsNothingOut() {
    show()
    compose.onNodeWithContentDescription("Settings").performClick()
    compose.onNodeWithText("Sign Out").performScrollTo().performClick()
    compose.onNodeWithText("Cancel").performClick()
    compose.onAllNodesWithText("Sign out of iCloud on this phone?").assertCountEquals(0)
    assertFalse("signOut" in fake.calls)
    assertTrue(signedOut.isEmpty())
  }

  @Test
  fun anEndedSessionAsksToSignInAgainAndNothingShowsOtherwise() {
    show()
    compose.onAllNodesWithText("Sign In Again").assertCountEquals(0)
    compose.onAllNodesWithText("sync", substring = true, ignoreCase = true).assertCountEquals(0)
    sessionEnded = true
    compose.waitForIdle()
    compose.onNodeWithText("Apple ended this phone's sign-in", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Sign In Again").performClick()
    assertEquals(listOf("sign in"), signIns.toList())
    sessionEnded = false
    compose.waitForIdle()
    compose.onAllNodesWithText("Sign In Again").assertCountEquals(0)
  }

  @Test
  fun aFailedSyncSaysSoAndTriesAgain() {
    show()
    sync = upToDate.copy(last = mapOf(NOTES_AUTHORITY to Outcome.Last(at = now, ok = false, said = "Apple didn't answer", okAt = now - 3 * 3_600_000)))
    compose.waitForIdle()
    compose.onNodeWithText("uNotes cloud will try again on its own", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Try Again").performClick()
    assertEquals(listOf("sync"), synced.toList())
  }

  /** Advanced Data Protection asks for Notes alone here, whatever else it holds, in the app's name. */
  @Test
  fun dataProtectionAsksForNotesAlone() {
    fake.standing = DataAccessNow(DataState.NEEDED, listOf("notes", "photos", "drive"), null)
    show()
    compose.onNodeWithText(
      "Advanced Data Protection keeps Notes until you approve uNotes cloud on one of your devices. Everything on this phone stays.",
    ).assertIsDisplayed()
    compose.onNodeWithText("Approve on your iPhone").assertIsDisplayed()
  }
}
