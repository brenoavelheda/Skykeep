package io.github.yashwanthkamireddi.skykeep.ui

import io.github.yashwanthkamireddi.skykeep.sync.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What uNotes cloud's first screen says above the folders: one thing at most, the most pressing,
 * and nothing when all is well. Notes' last sync is the adapter's record ([Outcome.Last]).
 */
class NotesNeedTest {
  private val now = 1_800_000_000_000L
  private val hour = 3_600_000L

  private fun ok(at: Long = now - hour) = Outcome.Last(at = at, ok = true, said = "12 notes · 1 changed", okAt = at)
  private fun failed(said: String = "Apple didn't answer", okAt: Long = now - 3 * hour) =
    Outcome.Last(at = now - hour, ok = false, said = said, okAt = okAt)

  private fun need(sessionEnded: Boolean = false, last: Outcome.Last? = ok(), online: Boolean = true) =
    notesNeed(sessionEnded, last, online, now)

  @Test
  fun quietWhenTheLastSyncWentWell() {
    assertNull(need())
  }

  @Test
  fun quietBeforeTheFirstSync() {
    assertNull(need(last = null))
  }

  @Test
  fun appleEndingTheSessionAsksToSignInAgain() {
    assertEquals(NotesNeed.SignInAgain, need(sessionEnded = true))
    // Said first, offline or not: nothing syncs again until the person signs in.
    assertEquals(NotesNeed.SignInAgain, need(sessionEnded = true, online = false))
  }

  @Test
  fun aSyncRefusedForTheSessionAsksToSignInAgain() {
    assertEquals(NotesNeed.SignInAgain, need(last = failed("Your session has ended")))
    assertEquals(NotesNeed.SignInAgain, need(last = failed("Sign in again to keep syncing")))
  }

  @Test
  fun aFailedSyncSaysSo() {
    assertEquals(NotesNeed.Failing, need(last = failed()))
  }

  @Test
  fun offlineIsSaidInsteadOfTheFailureItCaused() {
    assertEquals(NotesNeed.Offline, need(last = failed(), online = false))
    assertEquals(NotesNeed.Offline, need(online = false))
  }

  /** Advanced Data Protection holding Notes is waiting on the person, not failing: its own notice says so. */
  @Test
  fun waitingForApprovalIsNotAFailure() {
    assertNull(need(last = failed().copy(paused = true)))
  }

  @Test
  fun twoDaysWithoutAGoodSyncSaySo() {
    assertEquals(NotesNeed.Stalled, need(last = ok(at = now - 49 * hour)))
    assertNull(need(last = ok(at = now - 47 * hour)))
  }
}
