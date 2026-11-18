package io.github.yashwanthkamireddi.skykeep.ui

import io.github.yashwanthkamireddi.skykeep.sync.Outcome

/**
 * What uNotes cloud's first screen says above the folders about syncing: the states the suite's
 * Home shows on its Notes card and its notices, as one line or card at most. Advanced Data
 * Protection is not here: its own notice ([DataProtection]) asks the engine, not the record.
 */
internal enum class NotesNeed {
  /** Apple ended the session: nothing syncs again until the person signs in. */
  SignInAgain,
  /** No network: the notes are on the phone, and changes wait to be sent. */
  Offline,
  /** The last sync failed; the next one tries again by itself. */
  Failing,
  /** Nothing has synced cleanly for two days, though it has before: a battery saver, usually. */
  Stalled,
}

/** The suite's two days (Home's battery notice) before a quiet phone is worth saying. */
private const val STALLED_AFTER = 48 * 3_600_000L

/**
 * The one thing to say, the most pressing first; null when all is well. A sign-in Apple refused
 * is told by [sessionEnded] (SessionEnded marks it) or by the sync's own words, as Home's Health
 * card reads them. Offline goes before a failure, which offline is the likely cause of.
 */
internal fun notesNeed(sessionEnded: Boolean, last: Outcome.Last?, online: Boolean, now: Long): NotesNeed? {
  val refused = last != null && !last.ok && !last.paused
  val signIn = refused && (last!!.said.contains("session", ignoreCase = true) || last.said.contains("sign in", ignoreCase = true))
  return when {
    sessionEnded || signIn -> NotesNeed.SignInAgain
    !online -> NotesNeed.Offline
    refused -> NotesNeed.Failing
    last != null && !last.paused && last.okAt > 0 && now - last.okAt > STALLED_AFTER -> NotesNeed.Stalled
    else -> null
  }
}
