package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailSignatureStoreTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun `account signature persists independently and removal cascades without changing saved draft`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val first = account()
        val second = account()
        val secrets = FakeCredentialStore()
        val signature = MailSignature("Fixture λ\nSecond line", enabled = true, replies = false)
        var draftId = ""
        MailDatabase(path).use { database ->
            val accounts = UtAccountManager(AccountSettingsStorage(database), secrets)
            accounts.createWithPasswords(first, AccountPasswords("fixture", "fixture"))
            accounts.createWithPasswords(second, AccountPasswords("fixture", "fixture"))
            val testSubject = MailSignatureStore(database)
            assertThat(testSubject.read(first.id)).isEqualTo(MailSignature())
            testSubject.save(first.id, signature)
            val draft = OutgoingMailStore(database).create(first.id, signature.apply(ResponseSeed()))
            draftId = draft.id
            testSubject.save(first.id, signature.copy(text = "Changed"))
            assertThat(
                OutgoingMailStore(database).get(first.id, draft.id).body,
            ).isEqualTo("\n\n-- \nFixture λ\nSecond line")
            assertThat(testSubject.read(second.id)).isEqualTo(MailSignature())
        }
        MailDatabase(path).use { database ->
            val testSubject = MailSignatureStore(database)
            assertThat(testSubject.read(first.id).text).isEqualTo("Changed")
            assertThat(OutgoingMailStore(database).get(first.id, draftId).body.contains("Fixture λ")).isTrue()
            UtAccountManager(AccountSettingsStorage(database), secrets).delete(first.id)
            assertThat(database.query("SELECT count(*) FROM signatures") { getLong(0) }.single()).isEqualTo(0L)
            assertThat(testSubject.read(second.id)).isEqualTo(MailSignature())
        }
    }

    @Test
    fun `schema ten upgrades without altering account draft or enabling a default signature`() {
        val path = directory.root.toPath().resolve("mail.sqlite")
        val account = account()
        var draftId = ""
        MailDatabase(path).use { database ->
            UtAccountManager(AccountSettingsStorage(database), FakeCredentialStore())
                .createWithPasswords(account, AccountPasswords("fixture", "fixture"))
            draftId = OutgoingMailStore(database).create(account.id, ResponseSeed(body = "Existing draft")).id
            database.execute("DROP TABLE signatures")
            database.execute("PRAGMA user_version=10")
        }
        MailDatabase(path).use { database ->
            assertThat(database.query("PRAGMA user_version") { getLong(0) }.single()).isEqualTo(16L)
            assertThat(OutgoingMailStore(database).get(account.id, draftId).body).isEqualTo("Existing draft")
            assertThat(MailSignatureStore(database).read(account.id)).isEqualTo(MailSignature())
            assertThat(database.query("PRAGMA quick_check") { getText(0) }.single()).isEqualTo("ok")
        }
    }

    @Test
    fun `signature preferences preserve reply threading and explicit forward policy`() {
        val seed =
            ResponseSeed(
                body = "\n\n> Quote",
                inReplyTo = "<parent@example.invalid>",
                references = "<root@example.invalid>",
            )
        val testSubject = MailSignature("Fixture\r\nTeam", enabled = true, forwards = false)
        val reply = testSubject.apply(seed, ResponseMode.REPLY_ALL)
        assertThat(reply).isEqualTo(seed.copy(body = "\n\n-- \nFixture\nTeam\n\n> Quote"))
        assertThat(testSubject.apply(seed, ResponseMode.FORWARD)).isEqualTo(seed)
        assertThat(testSubject.copy(enabled = false).apply(seed)).isEqualTo(seed)
        assertThat(testSubject.toString()).isEqualTo("MailSignature(redacted)")
    }

    @Test
    fun `invalid signature cannot be stored and foreign account writes fail`() {
        assertThat(runCatching { MailSignature("bad\u0000text") }.isFailure).isTrue()
        assertThat(runCatching { MailSignature("a".repeat(MAX_SIGNATURE_CHARACTERS + 1)) }.isFailure).isTrue()
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            assertThat(
                runCatching {
                    MailSignatureStore(database).save(AccountIdFactory.create(), MailSignature())
                }.isFailure,
            ).isTrue()
        }
    }

    private fun account() = UtMailAccount(
        AccountIdFactory.create(),
        "Fixture",
        "fixture@example.invalid",
        server("imap", 993),
        server("smtp", 465),
        UUID.randomUUID().toString(),
    )
    private fun server(kind: String, port: Int) = ServerSettings(
        kind,
        "localhost",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture",
        null,
        null,
    )
}
