package io.github.yashwanthkamireddi.skykeep.ui.notes

import android.content.Context
import androidx.annotation.StringRes
import io.github.yashwanthkamireddi.skykeep.R

/** The kinds of attachment a file-open is asked for: the ones handed to another app. A picture,
 *  a drawing, a scan and a link are drawn and opened in the note itself. */
private val OPENABLE = setOf("document", "audio", "video", "file")

/** Whether an attachment of this kind is a file to hand to another app at all. */
internal fun openableKind(kind: String): Boolean = kind in OPENABLE

/**
 * Why a file in a note did not open, each with its own sentence: offline, nothing kept by
 * Apple, a kind Skykeep cannot open, no app for it, or the engine refusing. The same red line
 * for all of them said "not downloaded yet" whether or not that was true.
 */
enum class AttachmentProblem(@StringRes val text: Int) {
  Offline(R.string.note_attachment_offline),
  NothingToOpen(R.string.note_attachment_nothing),
  Unsupported(R.string.note_attachment_unsupported),
  NoApp(R.string.note_attachment_no_app),
  Failed(R.string.note_attachment_failed),
}

/**
 * What stopped a file from opening, or null when nothing did. `bytes` is what the engine handed
 * over (empty when Apple keeps no file), `reason` the engine's refusal (null when it answered),
 * and `online` whether the phone has a network at all. Offline is said as offline, never as a
 * file Apple does not have.
 */
fun attachmentProblem(
  kind: String,
  bytes: ByteArray?,
  reason: String?,
  online: Boolean,
): AttachmentProblem? = when {
  kind !in OPENABLE -> AttachmentProblem.Unsupported
  reason != null && !online -> AttachmentProblem.Offline
  reason != null -> AttachmentProblem.Failed
  bytes != null && bytes.isEmpty() -> AttachmentProblem.NothingToOpen
  else -> null
}

/** The sentence to show, with the engine's own reason after it where there is one. */
fun AttachmentProblem.said(context: Context, reason: String? = null): String =
  if (this == AttachmentProblem.Failed) {
    context.getString(
      text,
      reason?.takeIf { it.isNotBlank() } ?: context.getString(R.string.note_attachment_failed_unknown),
    )
  } else {
    context.getString(text)
  }
