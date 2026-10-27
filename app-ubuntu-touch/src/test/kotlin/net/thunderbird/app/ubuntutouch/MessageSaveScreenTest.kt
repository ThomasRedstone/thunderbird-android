package net.thunderbird.app.ubuntutouch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import assertk.assertThat
import assertk.assertions.isEqualTo
import net.thunderbird.components.ui.bolt.atom.Surface
import net.thunderbird.components.ui.bolt.theme.thunderbird.ThunderbirdBoltTheme
import net.thunderbird.components.ui.testing.ComposeUiTestHarness
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class MessageSaveScreenTest : ComposeUiTestHarness() {
    @Test
    fun `attachment picker preserves exact reference and busy prevents selection`() = runComposeTest {
        val reference = AttachmentReference(AccountIdFactory.create(), "Archive", "42", "fixture", 1)
        val current = mutableStateOf(
            MessageSaveState(
                attachments = listOf(CachedAttachment(reference, "café.bin", "application/octet-stream")),
            ),
        )
        val events = mutableListOf<MessageSaveEvent>()
        setContent { ThunderbirdBoltTheme { MessageSaveControls(current.value, events::add) } }
        onNodeWithText("café.bin").performClick()
        assertThat(events).isEqualTo(
            listOf(
                MessageSaveEvent.Prepare(
                    reference.account,
                    reference.folder,
                    reference.uid,
                    reference,
                ),
            ),
        )
        current.value = current.value.copy(busy = true)
        onNodeWithText("café.bin").assertIsNotEnabled()
    }

    @Test
    fun `reader attachment action passes cached message context`() = runComposeTest {
        val account = AccountIdFactory.create()
        val current = LiveMailState(
            emptyList(),
            account = account,
            folder = MailFolder("Archive", "Archive"),
            content = MailContent(
                MailSummary("42", "Fixture", "Sender", false),
                "Body",
                true,
                attachments = listOf(MailAttachmentInfo("file.bin", "application/octet-stream")),
            ),
        )
        val events = mutableListOf<MessageSaveEvent>()
        setContent { ThunderbirdBoltTheme { ReaderAttachmentAction(current, events::add) } }
        onNodeWithText("Save attachments").performClick()
        assertThat(events).isEqualTo(listOf(MessageSaveEvent.Attachments(MailMessageKey(account, "Archive", "42"))))
    }

    @Test
    fun `terminal receiver states show their confirmed outcome rather than a blank picker`() = runComposeTest {
        val current = mutableStateOf(
            MessageSaveState(pending = ExportReconcileResult(null, ExportBrokerState.FINALIZED)),
        )
        setContent {
            ThunderbirdBoltTheme { Surface { MessageSaveControls(current.value) {} } }
        }
        onNodeWithText("The transfer service confirmed completion.").assertIsDisplayed()
        current.value = MessageSaveState(pending = ExportReconcileResult(null, ExportBrokerState.ABORTED))
        onNodeWithText("The transfer service confirmed cancellation.").assertIsDisplayed()
        onNodeWithText("The transfer service confirmed completion.").assertDoesNotExist()
    }

    @Test
    fun `complete reader passes actual account folder and uid and busy disables save`() = runComposeTest {
        val account = AccountIdFactory.create()
        val current = mutableStateOf(
            LiveMailState(
                emptyList(),
                account = account,
                folder = MailFolder("Archive", "Archive"),
                content = MailContent(MailSummary("42", "Fixture", "Sender", false), "Fixture", true),
            ),
        )
        val events = mutableListOf<MessageSaveEvent>()
        setContent {
            ThunderbirdBoltTheme {
                Surface { LiveMailScreen(current.value, onSave = events::add, onEvent = {}) }
            }
        }
        onNodeWithText("Save message (.eml)").performClick()
        assertThat(events).isEqualTo(listOf(MessageSaveEvent.Prepare(account, "Archive", "42")))
        current.value = current.value.copy(busy = true)
        onNodeWithText("Save message (.eml)").assertIsNotEnabled()
        current.value = current.value.copy(content = current.value.content?.copy(complete = false))
        onNodeWithText("Save message (.eml)").assertDoesNotExist()
    }

    @Test
    fun `uncertain transfer cleanup requires review and names the limited effect`() = runComposeTest {
        val record = ExportLeaseRecord(
            java.util.UUID.randomUUID(),
            AccountIdFactory.create(),
            "attachment-fixture/message.eml",
            ExportLeasePhase.CHARGING,
            ExportTransferReference(":1.42", "/transfers/fixture/export/1"),
        )
        val current = mutableStateOf(MessageSaveState(pending = ExportReconcileResult(record)))
        val events = mutableListOf<MessageSaveEvent>()
        setContent {
            ThunderbirdBoltTheme { Surface { MessageSaveControls(current.value, events::add) } }
        }
        onNodeWithText("Delete temporary copy").assertDoesNotExist()
        onNodeWithText("Review temporary copy cleanup").performClick()
        assertThat(events).isEqualTo(listOf(MessageSaveEvent.ReviewDiscard))
        current.value = current.value.copy(confirmDiscard = true)
        onNodeWithText("Delete temporary copy").performClick()
        assertThat(events.last()).isEqualTo(MessageSaveEvent.ConfirmDiscard)
    }
}
