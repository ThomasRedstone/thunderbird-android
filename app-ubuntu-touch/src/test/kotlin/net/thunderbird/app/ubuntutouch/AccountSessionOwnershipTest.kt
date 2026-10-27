package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.mail.AuthType
import com.fsck.k9.mail.ConnectionSecurity
import com.fsck.k9.mail.ServerSettings
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountSessionOwnershipTest {
    @get:Rule val directory = TemporaryFolder()
    private val credentials = FakeCredentialStore()
    private val passwords = AccountPasswords("incoming", "outgoing")

    @Test
    fun `replacement retires only that account while rename preserves sessions`() = fixture { _, owner ->
        val account = create(owner)
        val other = create(owner)
        val first = register(owner, account)
        val second = register(owner, other)
        try {
            owner.updateSync(account.id) { it.copy(displayName = "Renamed") }
            assertThat(first.isRetired).isFalse()
            owner.replacePasswords(account.id, passwords)
            assertThat(first.isRetired).isTrue()
            assertThat(second.isRetired).isFalse()
            assertThat(runCatching { first.ensureActive() }.isFailure).isTrue()
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `locked deletion and failed replacement retain current authorization sessions`() = fixture { database, owner ->
        val account = create(owner)
        register(owner, account).use { resources ->
            credentials.locked = true
            assertThat(runCatching { owner.delete(account.id) }.isFailure).isTrue()
            assertThat(resources.isRetired).isFalse()
            credentials.locked = false
            failMutation(database, "UPDATE")
            assertThat(runCatching { owner.replacePasswords(account.id, passwords) }.isFailure).isTrue()
            assertThat(resources.isRetired).isFalse()
            assertThat(owner.findById(account.id)).isEqualTo(account)
        }
    }

    @Test
    fun `successful credential deletion retires sessions even if subsequent SQL delete fails`() = fixture {
            database,
            owner,
        ->
        val account = create(owner)
        register(owner, account).use { resources ->
            failMutation(database, "DELETE")
            assertThat(runCatching { owner.delete(account.id) }.isFailure).isTrue()
            assertThat(resources.isRetired).isTrue()
            assertThat(owner.findById(account.id)).isEqualTo(account)
            assertThat(credentials.values.isEmpty()).isTrue()
        }
    }

    @Test
    fun `teardown runs outside manager monitor and deletion closes every owned resource`() = fixture { _, owner ->
        val account = create(owner)
        val other = create(owner)
        val worker = Executors.newSingleThreadExecutor()
        var closed = 0
        register(owner, account).use { resources ->
            resources.own(
                AutoCloseable {
                    worker.submit<Boolean> { owner.passwords(other.id) == passwords }.get(3, TimeUnit.SECONDS)
                    closed++
                },
            )
            try {
                owner.delete(account.id)
                assertThat(closed).isEqualTo(1)
                assertThat(resources.isRetired).isTrue()
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `old construction snapshot cannot register after replacement or identical recreation`() = fixture { _, owner ->
        val account = create(owner)
        owner.delete(account.id)
        owner.createWithPasswords(account, passwords)
        MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory())).use { resources ->
            assertThat(runCatching { owner.registerSession(account, resources) }.isFailure).isTrue()
            owner.registerSession(owner.findById(account.id)!!, resources)
            owner.releaseSession(resources)
            owner.replacePasswords(account.id, passwords)
            assertThat(resources.isRetired).isFalse()
        }
    }

    @Test
    fun `nested mutation from account transform is rejected before changing credentials`() = fixture { _, owner ->
        val account = create(owner)
        register(owner, account).use { resources ->
            assertThat(
                runCatching {
                    owner.updateSync(account.id) {
                        owner.replacePasswords(account.id, passwords)
                        it
                    }
                }.isFailure,
            ).isTrue()
            assertThat(owner.findById(account.id)).isEqualTo(account)
            assertThat(resources.isRetired).isFalse()
        }
    }

    private fun failMutation(database: MailDatabase, operation: String) {
        database.execute(
            "CREATE TRIGGER fail_mutation BEFORE $operation ON accounts BEGIN SELECT RAISE(ABORT,'fixture'); END",
        )
    }

    private fun register(owner: UtAccountManager, account: UtMailAccount): MailSessionResources {
        val resources = MailSessionResources(CancellableSocketFactory(JvmTrustedSocketFactory()))
        owner.registerSession(owner.findById(account.id)!!, resources)
        resources.own(AutoCloseable { owner.releaseSession(resources) })
        return resources
    }

    private fun create(owner: UtAccountManager): UtMailAccount {
        val id = AccountIdFactory.create()
        val account = UtMailAccount(
            id,
            "Controlled",
            "fixture@example.invalid",
            server("imap", 993),
            server("smtp", 465),
            UUID.randomUUID().toString(),
        )
        owner.createWithPasswords(account, passwords)
        return owner.findById(id)!!
    }

    private fun server(type: String, port: Int) = ServerSettings(
        type,
        "mail.example.invalid",
        port,
        ConnectionSecurity.SSL_TLS_REQUIRED,
        AuthType.PLAIN,
        "fixture@example.invalid",
        null,
        null,
    )

    private fun fixture(block: (MailDatabase, UtAccountManager) -> Unit) {
        MailDatabase(directory.root.toPath().resolve("mail.sqlite")).use { database ->
            block(database, UtAccountManager(AccountSettingsStorage(database), credentials))
        }
    }
}
