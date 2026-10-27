package net.thunderbird.app.ubuntutouch

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountRepository
import net.thunderbird.feature.mail.account.api.AccountManager

/** Single application owner. Secrets are never embedded in observable accounts or their database rows. */
@Suppress("TooManyFunctions") // Shared AccountManager/AccountRepository contract plus credential lifecycle.
internal class UtAccountManager(
    private val storage: AccountSettingsStorage,
    private val credentials: CredentialStore,
) : AccountManager<UtMailAccount>, AccountRepository {
    private val accounts = MutableStateFlow(storage.all())

    override fun findAll(): List<UtMailAccount> = accounts.value
    override fun observeAll(): Flow<List<UtMailAccount>> = accounts
    override fun findById(accountId: AccountId): UtMailAccount? = accounts.value.find { it.id == accountId }
    override fun observeById(accountId: AccountId): Flow<UtMailAccount?> =
        accounts.map { list -> list.find { it.id == accountId } }.distinctUntilChanged()

    override suspend fun create(account: UtMailAccount) = withContext(Dispatchers.IO) { createSync(account) }

    @Synchronized
    override fun createSync(account: UtMailAccount) {
        check(findById(account.id) == null) { "Account already exists" }
        check(findAll().none { it.credentialKey == account.credentialKey }) { "Credentials must belong to one account" }
        requireCredentials(account.credentialKey)
        storage.insert(account)
        reload()
    }

    override suspend fun update(accountId: AccountId, transform: (UtMailAccount) -> UtMailAccount): UtMailAccount? =
        withContext(Dispatchers.IO) { updateSync(accountId, transform) }

    @Synchronized
    override fun updateSync(accountId: AccountId, transform: (UtMailAccount) -> UtMailAccount): UtMailAccount? {
        val current = findById(accountId) ?: return null
        val updated = transform(current)
        require(updated.id == accountId) { "Account identity cannot change" }
        require(updated.credentialKey == current.credentialKey) { "Use credential replacement to change passwords" }
        storage.update(updated)
        reload()
        return updated
    }

    @Synchronized
    override fun moveAccount(accountId: AccountId, newPosition: Int) {
        val reordered = findAll().toMutableList()
        require(newPosition in reordered.indices)
        val account = reordered.single { it.id == accountId }
        reordered.remove(account)
        reordered.add(newPosition, account)
        storage.move(reordered.map { it.id })
        reload()
    }

    /** Write new immutable secret first; the SQL commit claims it. Journal enables crash/failure cleanup. */
    @Synchronized
    fun createWithPasswords(account: UtMailAccount, passwords: AccountPasswords) {
        check(findById(account.id) == null) { "Account already exists" }
        require(findAll().none { it.credentialKey == account.credentialKey })
        storage.scheduleCredentialCleanup(account.credentialKey)
        writePasswords(account.credentialKey, passwords)
        createSync(account)
    }

    @Synchronized
    fun replacePasswords(accountId: AccountId, passwords: AccountPasswords): Boolean {
        val current = checkNotNull(findById(accountId)) { "Account not found" }
        return replaceAccountAndPasswords(current, passwords)
    }

    @Synchronized
    fun replaceAccountAndPasswords(account: UtMailAccount, passwords: AccountPasswords): Boolean {
        val current = checkNotNull(findById(account.id)) { "Account not found" }
        require(account.credentialKey == current.credentialKey)
        val replacement = account.copy(credentialKey = UUID.randomUUID().toString())
        storage.scheduleCredentialCleanup(replacement.credentialKey)
        writePasswords(replacement.credentialKey, passwords)
        storage.scheduleCredentialCleanup(current.credentialKey)
        storage.update(replacement)
        reload()
        return cleanupUnusedCredentials()
    }

    @Synchronized
    fun passwords(accountId: AccountId): AccountPasswords {
        val account = checkNotNull(findById(accountId)) { "Account not found" }
        val value = credentials.read(account.credentialKey) ?: throw SecretStoreException(SecretFailure.UNAVAILABLE)
        return try {
            AccountPasswords.decode(value)
        } finally {
            value.fill(0)
        }
    }

    @Synchronized
    override fun delete(accountId: AccountId) {
        val account = findById(accountId) ?: return
        // Refuse removal while the keyring is locked/unavailable, rather than leave a live orphan credential.
        storage.scheduleCredentialCleanup(account.credentialKey)
        credentials.delete(account.credentialKey)
        storage.delete(accountId)
        reload()
        storage.completeCredentialCleanup(account.credentialKey)
    }

    @Synchronized
    fun cleanupUnusedCredentials(): Boolean {
        try {
            storage.unusedCredentialKeys().forEach { key ->
                credentials.delete(key)
                storage.completeCredentialCleanup(key)
            }
        } catch (_: SecretStoreException) {
            // The active account remains committed; report pending cleanup rather than a failed password change.
            return false
        }
        return true
    }

    private fun writePasswords(key: String, passwords: AccountPasswords) {
        val bytes = passwords.encode()
        try {
            credentials.write(key, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun requireCredentials(key: String) {
        val value = credentials.read(key) ?: throw SecretStoreException(SecretFailure.UNAVAILABLE)
        try {
            AccountPasswords.decode(value)
        } finally {
            value.fill(0)
        }
    }

    private fun reload() {
        accounts.value = storage.all()
    }
}
