package io.github.yashwanthkamireddi.skykeep.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yashwanthkamireddi.skykeep.R
import io.github.yashwanthkamireddi.skykeep.sync.NOTES_AUTHORITY
import io.github.yashwanthkamireddi.skykeep.sync.Outcome
import io.github.yashwanthkamireddi.skykeep.sync.SessionEnded
import io.github.yashwanthkamireddi.skykeep.ui.theme.AppTheme
import io.github.yashwanthkamireddi.skykeep.ui.theme.LocalAccent
import io.github.yashwanthkamireddi.skykeep.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.quince_mobile.AccountInterface

/**
 * uNotes cloud in Notes' colours from its first screen to its last: the accent roles, and the
 * marks the welcome draws in the accent. Off (the suite), the content as it is.
 */
@Composable
fun NotesOnlyTheme(on: Boolean, content: @Composable () -> Unit) {
  if (!on) return content()
  AppTheme(Palette.App.Notes) {
    CompositionLocalProvider(LocalAccent provides MaterialTheme.colorScheme.primary, content = content)
  }
}

/** A page over the notes, opened from their Settings button. */
private enum class NotesOnlyPage { Settings, Licences }

/**
 * uNotes cloud once signed in: the notes, filling the screen, with no bottom bar and no other
 * module. What the suite keeps on Home and in Settings that is Notes' own is here: Settings (the
 * account, syncing, sign out, licences) from the folders' top bar, and above the folders what
 * needs the person. Sign-in comes up through [onSignedOut], after Sign Out or when Apple ended
 * the session.
 */
@Composable
fun NotesOnlyScreens(engine: AccountInterface, onSignedOut: () -> Unit) {
  val context = LocalContext.current
  // Made by MainActivity as the notes open, so it may not be here yet on the first frame:
  // read again whenever Android's accounts change.
  var account by remember { mutableStateOf(phoneAccount(context)) }
  DisposableEffect(Unit) {
    val manager = AccountManager.get(context)
    val listener = OnAccountsUpdateListener { account = phoneAccount(context) }
    manager.addOnAccountsUpdatedListener(listener, null, true)
    onDispose { manager.removeOnAccountsUpdatedListener(listener) }
  }
  var asked by remember { mutableLongStateOf(0L) }
  val sync = rememberSyncView(account, asked)
  // Apple ended the session: SessionEnded marks the account, read again after each sync.
  val ended by produceState(false, sync.last) {
    value = withContext(Dispatchers.IO) {
      runCatching { engine.accountTag()?.let { SessionEnded.ended(context, it) } == true }.getOrDefault(false)
    }
  }
  NotesOnly(
    engine = engine, account = account, sync = sync, sessionEnded = ended, online = rememberOnline(),
    onSync = { account?.let { asked = System.currentTimeMillis(); syncNow(it) } },
    onSignIn = onSignedOut, onSignedOut = onSignedOut,
  )
}

/**
 * The notes and their pages, from what the phone says now (given, so a test can say it). Back
 * goes up one level: Licences to Settings, Settings to the notes; on the folders it leaves the
 * app, and inside the notes their own Back goes first.
 */
@Composable
internal fun NotesOnly(
  engine: AccountInterface,
  account: Account?,
  sync: SyncView,
  sessionEnded: Boolean,
  online: Boolean,
  onSync: () -> Unit,
  onSignIn: () -> Unit,
  onSignedOut: () -> Unit,
) {
  var page by rememberSaveable { mutableStateOf<NotesOnlyPage?>(null) }
  BackHandler(enabled = page != null) { page = if (page == NotesOnlyPage.Licences) NotesOnlyPage.Settings else null }
  // Kept for the note page's own full-screen moments (the attachment viewer); there is no bar
  // here for it to hide.
  val full = remember { mutableStateOf(false) }
  CompositionLocalProvider(LocalFullScreen provides full) {
    Scaffold { inner ->
      Box(Modifier.padding(inner).fillMaxSize()) {
        AppTheme(Palette.App.Notes) {
          when (page) {
            NotesOnlyPage.Licences -> LicencesPage { page = NotesOnlyPage.Settings }
            NotesOnlyPage.Settings -> NotesSettings(
              engine, account, sync, online, onSync,
              onBack = { page = null }, onSignedOut = onSignedOut, onLicences = { page = NotesOnlyPage.Licences },
            )
            null -> NotesPage(
              engine,
              // Where Android's notes apps keep it: the top bar of the first screen.
              folderActions = {
                IconButton(onClick = { page = NotesOnlyPage.Settings }) {
                  Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                }
              },
              folderNotices = {
                NotesNotices(engine, sync, sessionEnded, online, onSync, onSignIn, onReview = { page = NotesOnlyPage.Settings })
              },
            )
          }
        }
      }
    }
  }
}

/**
 * Above the folders, only when something needs the person, in the suite's words and its
 * [Notice] cards: Apple ended the session, Advanced Data Protection waits for approval, syncing
 * failed or stopped, changes held or not accepted, offline. Nothing at all when all is well.
 */
@Composable
private fun NotesNotices(
  engine: AccountInterface, sync: SyncView, sessionEnded: Boolean, online: Boolean,
  onSync: () -> Unit, onSignIn: () -> Unit, onReview: () -> Unit,
) {
  val context = LocalContext.current
  val app = stringResource(R.string.app_name)
  val last = sync.last[NOTES_AUTHORITY]
  // "3 hours ago" stays true while the notes are open: the time moves on each minute.
  val now by produceState(System.currentTimeMillis()) {
    while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() }
  }
  val since = last?.okAt?.takeIf { it > 0 }?.let { " Last synced ${ago(it, now)}." } ?: ""
  val notSent = rememberNotSent(engine, last?.at)
  Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    when (notesNeed(sessionEnded, last, online, now)) {
      NotesNeed.SignInAgain -> Notice(
        "Apple ended this phone's sign-in. Sign in again to keep your notes syncing. Nothing is lost, and changes waiting to send are kept.",
        warning = true,
      ) { Button(onClick = onSignIn) { Text("Sign In Again") } }
      NotesNeed.Offline -> Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
          "Offline. Your notes are on this phone, and changes you make will send when you're back online.",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      NotesNeed.Failing -> Notice("Notes couldn't sync: Apple didn't answer. $app will try again on its own.$since") {
        TextButton(onClick = onSync, enabled = !sync.busy) { Text("Try Again") }
      }
      NotesNeed.Stalled -> {
        // Phone makers' battery savers pause apps that aren't opened often, and a sync with them.
        val brand = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        Notice(
          "$app hasn't synced for two days. $brand phones can pause apps that aren't opened often. Allow $app to run in the background so your notes stay up to date.",
          warning = true,
        ) {
          Button(onClick = {
            runCatching {
              context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
          }) { Text("Battery Settings") }
          TextButton(onClick = {
            runCatching {
              context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/${android.os.Build.MANUFACTURER.lowercase()}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
          }) { Text("How on $brand") }
        }
      }
      null -> {}
    }
    DataProtection(engine, sync.last, onApproved = onSync)
    if (sync.held > 0 && !sync.busy) {
      Notice("${sync.held} changes are waiting for you. More than usual changed at once, so $app held them to be safe.") {
        Button(onClick = {
          AUTHORITIES.forEach { Outcome.confirmMassRemoval(context, it) }
          onSync()
        }) { Text("Apply Them") }
      }
    }
    if (notSent.isNotEmpty()) {
      Notice(
        if (notSent.size == 1) "1 change wasn't accepted by iCloud. It's kept for you in Settings."
        else "${notSent.size} changes weren't accepted by iCloud. They're kept for you in Settings.",
      ) { TextButton(onClick = onReview) { Text("Review") } }
    }
  }
}

/**
 * uNotes cloud's Settings: who is signed in, how Notes' syncing stands with Sync Now, the
 * changes iCloud didn't accept, what the app is, and Sign Out. The suite's pieces, without the
 * other modules' settings.
 */
@Composable
private fun NotesSettings(
  engine: AccountInterface, account: Account?, sync: SyncView, online: Boolean, onSync: () -> Unit,
  onBack: () -> Unit, onSignedOut: () -> Unit, onLicences: () -> Unit,
) {
  val context = LocalContext.current
  val app = stringResource(R.string.app_name)
  Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Notes") }
    Text("Settings", style = MaterialTheme.typography.headlineLarge)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
      // Read again after each sync: the session (name, address) is open by then.
      Box(Modifier.fillMaxWidth().padding(16.dp)) { AccountRow(engine, account, key = sync.last[NOTES_AUTHORITY]?.at) }
    }
    SectionLabel("Sync")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
          Text("Notes", style = MaterialTheme.typography.titleMedium)
          Text(syncStatus(sync, online), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onSync, enabled = account != null && !sync.busy && online) { Text("Sync Now") }
      }
    }
    NotSentSection(engine, account)
    SectionLabel("About")
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$app${version?.let { " $it" } ?: ""}", style = MaterialTheme.typography.titleMedium)
        Text(
          "Your iCloud notes on this phone. Free and open source (GPL-3.0), built from Skykeep. Not made by or affiliated with Apple. iCloud, iPhone and Apple are trademarks of Apple Inc.",
          style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onLicences, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open-source licences") }
      }
    }
    Spacer(Modifier.height(8.dp))
    SignOutButton(engine, account, onSignedOut)
  }
}

/** How Notes' syncing stands, in the words the suite's Home card uses. */
private fun syncStatus(sync: SyncView, online: Boolean): String {
  val last = sync.last[NOTES_AUTHORITY]
  val signIn = last != null && !last.ok &&
    (last.said.contains("session", ignoreCase = true) || last.said.contains("sign in", ignoreCase = true))
  return when {
    NOTES_AUTHORITY in sync.active -> "Syncing"
    !online -> "Offline" + (last?.okAt?.takeIf { it > 0 }?.let { " · last synced ${ago(it)}" } ?: "")
    last == null -> if (sync.busy) "Setting up" else "Not synced yet"
    last.paused -> "Waiting for your approval"
    last.ok -> "Up to date · ${ago(last.at)}"
    signIn -> "Sign in again"
    else -> "Will try again" + (if (last.okAt > 0) " · last synced ${ago(last.okAt)}" else "")
  }
}
