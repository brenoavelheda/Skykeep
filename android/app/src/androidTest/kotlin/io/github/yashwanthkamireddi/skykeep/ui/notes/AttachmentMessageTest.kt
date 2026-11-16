package io.github.yashwanthkamireddi.skykeep.ui.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The note screen's sentence for a file that did not open. Each reason has its own words: one
 * red line for all of them said "not on the phone yet" whether or not that was true.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentMessageTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun each_reason_is_its_own_sentence() {
    val said = AttachmentProblem.entries.map { it.said(context) }
    assertTrue("every reason has words", said.all { it.isNotBlank() })
    assertEquals("no two reasons share a sentence", said.size, said.toSet().size)
  }

  @Test
  fun offline_is_said_as_offline_and_not_as_a_missing_file() {
    val problem = attachmentProblem("file", null, "Couldn't reach iCloud. Check your connection and try again.", online = false)
    assertEquals(AttachmentProblem.Offline, problem)
    assertTrue(problem!!.said(context).contains("offline"))
    assertNotEquals(AttachmentProblem.NothingToOpen.said(context), problem.said(context))
  }

  @Test
  fun a_file_apple_keeps_none_of_says_so() {
    val problem = attachmentProblem("file", ByteArray(0), null, online = true)
    assertEquals(AttachmentProblem.NothingToOpen, problem)
    assertTrue(problem!!.said(context).contains("Apple"))
  }

  @Test
  fun a_kind_skykeep_cannot_hand_over_says_so() {
    assertEquals(AttachmentProblem.Unsupported, attachmentProblem("scan", null, null, online = true))
    assertEquals(AttachmentProblem.Unsupported, attachmentProblem("link", null, null, online = true))
    assertEquals(null, attachmentProblem("document", byteArrayOf(1), null, online = true))
  }

  @Test
  fun a_kind_with_no_app_has_its_own_sentence() {
    val said = AttachmentProblem.NoApp.said(context)
    assertTrue(said.contains("No app"))
    assertNotEquals(AttachmentProblem.Unsupported.said(context), said)
  }

  @Test
  fun the_engines_reason_is_shown() {
    val said = AttachmentProblem.Failed.said(context, "iCloud is busy right now. Nothing was changed.")
    assertTrue(said.contains("iCloud is busy right now. Nothing was changed."))
    val unknown = AttachmentProblem.Failed.said(context, null)
    assertTrue(unknown.contains("Try again"))
  }
}
