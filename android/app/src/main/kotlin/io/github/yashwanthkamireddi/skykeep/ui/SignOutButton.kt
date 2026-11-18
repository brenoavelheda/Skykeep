package io.github.yashwanthkamireddi.skykeep.ui

import android.accounts.Account
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.yashwanthkamireddi.skykeep.BuildConfig
import io.github.yashwanthkamireddi.skykeep.R
import io.github.yashwanthkamireddi.skykeep.sync.SignOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign Out at the foot of Settings, and the question it asks first: what hasn't reached iCloud,
 * then Keep a Copy, Remove from Phone or Cancel. The suite's and uNotes cloud's Settings share
 * it. uNotes cloud has no contacts or calendars on the phone (nor the access to read them), so
 * there it counts the engine's changes alone and its copy is the notes kept in the app.
 */
@Composable
internal fun SignOutButton(engine: uniffi.quince_mobile.AccountInterface, account: Account?, onSignedOut: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val app = stringResource(R.string.app_name)
  var leaving by remember { mutableStateOf(false) }
  var unsent by remember { mutableIntStateOf(0) }
  var working by remember { mutableStateOf(false) }
  var trouble by remember { mutableStateOf<String?>(null) }
  OutlinedButton(
    modifier = Modifier.fillMaxWidth(),
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    enabled = !working,
    onClick = {
      scope.launch {
        // Everything not yet at Apple: the phone's contacts and calendars, and the notes,
        // reminders and photo changes waiting in the engine's library.
        unsent = withContext(Dispatchers.IO) {
          (if (BuildConfig.NOTES_ONLY) 0 else account?.let { SignOut.unsent(context.contentResolver, it) } ?: 0) +
            (runCatching { engine.unsentChanges().toInt() }.getOrDefault(0))
        }
        leaving = true
      }
    },
  ) { Text("Sign Out") }
  trouble?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  if (leaving) {
    AlertDialog(
      onDismissRequest = { leaving = false },
      title = { Text("Sign out of iCloud on this phone?") },
      text = {
        Text(
          (if (unsent > 0) "$unsent changes made on this phone haven't reached iCloud yet and won't be sent.\n\n" else "") +
            if (BuildConfig.NOTES_ONLY) {
              "Keep a Copy and your notes stay in $app on this phone, for when you sign in to this account again. Remove from Phone deletes them here. iCloud itself isn't changed either way."
            } else {
              "Keep a copy and your contacts and calendars stay on this phone as its own, no longer synced. iCloud itself isn't changed either way."
            },
        )
      },
      // Three choices, stacked as Material lays out more than two: the safe one first, the one
      // that removes in the error colour, Cancel last.
      confirmButton = {
        Column(horizontalAlignment = Alignment.End) {
          for ((label, keep) in listOf("Keep a Copy" to true, "Remove from Phone" to false)) {
            TextButton(
              onClick = {
                leaving = false
                working = true
                scope.launch {
                  val done = withContext(Dispatchers.IO) {
                    runCatching {
                      account?.let { a ->
                        if (keep && !BuildConfig.NOTES_ONLY) SignOut.keepCopy(context.contentResolver, a)
                        SignOut.removeAccount(context, a)
                      }
                      // Remove from Phone: the account's library and Skykeep's own copies go too,
                      // as the desktop's Remove does; nothing of it is left in the app's storage.
                      if (!keep) { engine.forgetAccount(); SignOut.forgetHere(context) }
                      engine.signOut()
                      io.github.yashwanthkamireddi.skykeep.widgets.WidgetUpdates.scheduleAll(context)
                    }
                  }
                  working = false
                  done.onSuccess { onSignedOut() }.onFailure { trouble = "Couldn't sign out: ${it.message}" }
                }
              },
              colors = if (keep) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(label) }
          }
          TextButton(onClick = { leaving = false }) { Text("Cancel") }
        }
      },
    )
  }
}
