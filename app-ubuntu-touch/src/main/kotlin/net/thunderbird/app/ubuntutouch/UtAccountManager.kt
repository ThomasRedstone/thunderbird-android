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
    private val oauthProviders: OAuthProviderRegistry = OAuthProviderRegistry(),
) : AccountManager<UtMailAccount>, AccountRepository {
    private val accounts = MutableStateFlow(storage.all())
    private val sessions = mutableMapOf<MailSessionResources, UtMailAccount>()

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
        requireCredentials(account)
        storage.insert(account)
        reload()
    }

    override suspend fun update(accountId: AccountId, transform: (UtMailAccount) -> UtMailAccount): UtMailAccount? =
        withContext(Dispatchers.IO) { updateSync(accountId, transform) }

    override fun updateSync(
        accountId: AccountId,
        transform: (UtMailAccount) -> UtMailAccount,
    ): UtMailAccount? = withSessionRetirement {
        val current = findById(accountId) ?: return@withSessionRetirement null
        val updated = transform(current)
        require(updated.id == accountId) { "Account identity cannot change" }
        require(updated.credentialKey == current.credentialKey) { "Use credential replacement to change credentials" }
        require(updated.oauthProviderId == current.oauthProviderId) { "Use credential replacement to change provider" }
        if (updated.isOAuth) {
            oauthProviders.configurationFor(updated)
            require(oauthProviders.identityRequirementFor(updated) == null || updated.address == current.address) {
                "Use reauthorization to change verified identity"
            }
        }
        storage.update(updated)
        if (!sameAuthorization(current, updated)) retireAccountSessions(accountId)
        reload()
        updated
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
        require(!account.isOAuth) { "OAuth accounts require authorization credentials" }
        check(findById(account.id) == null) { "Account already exists" }
        require(findAll().none { it.credentialKey == account.credentialKey })
        storage.scheduleCredentialCleanup(account.credentialKey)
        writePasswords(account.credentialKey, passwords)
        createSync(account)
    }

    fun replacePasswords(accountId: AccountId, passwords: AccountPasswords): Boolean {
        val current = synchronized(this) { checkNotNull(findById(accountId)) { "Account not found" } }
        return replaceAccountAndPasswords(current, passwords)
    }

    fun replaceAccountAndPasswords(
        account: UtMailAccount,
        passwords: AccountPasswords,
    ): Boolean = withSessionRetirement {
        require(!account.isOAuth) { "OAuth accounts require authorization credentials" }
        val current = checkNotNull(findById(account.id)) { "Account not found" }
        require(account.credentialKey == current.credentialKey)
        val replacement = account.copy(credentialKey = UUID.randomUUID().toString())
        storage.scheduleCredentialCleanup(replacement.credentialKey)
        writePasswords(replacement.credentialKey, passwords)
        storage.scheduleCredentialCleanup(current.credentialKey)
        storage.update(replacement)
        retireAccountSessions(replacement.id)
        reload()
        cleanupUnusedCredentials()
    }

    @Synchronized
    fun passwords(accountId: AccountId): AccountPasswords {
        val account = checkNotNull(findById(accountId)) { "Account not found" }
        require(!account.isOAuth) { "OAuth accounts do not use password credentials" }
        val value = credentials.read(account.credentialKey) ?: throw SecretStoreException(SecretFailure.UNAVAILABLE)
        return try {
            AccountPasswords.decode(value)
        } finally {
            value.fill(0)
        }
    }

    override fun delete(accountId: AccountId) = withSessionRetirement {
        val account = findById(accountId) ?: return@withSessionRetirement
        // Refuse removal while the keyring is locked/unavailable, rather than leave a live orphan credential.
        storage.scheduleCredentialCleanup(account.credentialKey)
        credentials.delete(account.credentialKey)
        retireAccountSessions(accountId)
        storage.delete(accountId)
        reload()
        storage.completeCredentialCleanup(account.credentialKey)
    }

    @Synchronized
    fun hasPendingCredentialCleanup(): Boolean = storage.unusedCredentialKeys().isNotEmpty()

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

    private fun requireCredentials(account: UtMailAccount) {
        if (account.isOAuth) {
            val configuration = oauthProviders.configurationFor(account)
            checkNotNull(
                OAuthTokenStore(credentials).read(OAuthGrant(account.id, account.credentialKey), configuration),
            ) {
                "OAuth credentials unavailable"
            }
        } else {
            val value = credentials.read(account.credentialKey) ?: throw SecretStoreException(SecretFailure.UNAVAILABLE)
            try {
                AccountPasswords.decode(value)
            } finally {
                value.fill(0)
            }
        }
    }

    @Synchronized
    fun createWithOAuth(account: UtMailAccount, tokens: OAuthTokens, verified: OAuthVerifiedIdentity? = null) {
        require(account.isOAuth) { "OAuth account metadata required" }
        check(findById(account.id) == null) { "Account already exists" }
        require(findAll().none { it.credentialKey == account.credentialKey })
        val configuration = oauthProviders.configurationFor(account)
        tokens.requireScopes(oauthProviders.requiredScopesFor(account))
        val bound = oauthProviders.bindIdentity(account, tokens, verified)
        storage.scheduleCredentialCleanup(account.credentialKey)
        OAuthTokenStore(credentials).write(OAuthGrant(account.id, account.credentialKey), configuration, bound)
        createSync(account)
    }

    fun replaceWithOAuth(
        expected: UtMailAccount,
        replacement: UtMailAccount,
        tokens: OAuthTokens,
        verified: OAuthVerifiedIdentity? = null,
    ): Boolean = withSessionRetirement {
        require(
            replacement.isOAuth && replacement.id == expected.id && replacement.credentialKey != expected.credentialKey,
        )
        check(findById(expected.id) == expected) { "Account changed during authorization" }
        require(findAll().none { it.credentialKey == replacement.credentialKey })
        val configuration = oauthProviders.configurationFor(replacement)
        tokens.requireScopes(oauthProviders.requiredScopesFor(replacement))
        val previous = previousVerifiedIdentity(expected, replacement, configuration, oauthProviders, credentials)
        val bound = oauthProviders.bindIdentity(replacement, tokens, verified, previous)
        storage.scheduleCredentialCleanup(replacement.credentialKey)
        OAuthTokenStore(credentials).write(OAuthGrant(replacement.id, replacement.credentialKey), configuration, bound)
        storage.scheduleCredentialCleanup(expected.credentialKey)
        storage.update(replacement)
        retireAccountSessions(replacement.id)
        reload()
        cleanupUnusedCredentials()
    }

    fun readOAuthCredential(grant: OAuthGrant): ByteArray? = withCurrentOAuthGrant(grant) {
        it.read(grant.credentialKey)
    }

    fun writeOAuthCredential(grant: OAuthGrant, value: ByteArray) = withCurrentOAuthGrant(grant) {
        it.write(grant.credentialKey, value)
    }

    fun deleteOAuthCredential(grant: OAuthGrant) = withCurrentOAuthGrant(grant) { it.delete(grant.credentialKey) }

    @Synchronized
    private fun <T> withCurrentOAuthGrant(grant: OAuthGrant, operation: (CredentialStore) -> T): T {
        val account = findById(grant.accountId)
        if (account == null || !account.isOAuth || account.credentialKey != grant.credentialKey) {
            throw StaleOAuthGrantException()
        }
        oauthProviders.configurationFor(account)
        return operation(credentials)
    }

    /** Registration and mutation share this monitor; teardown never runs while holding it. */
    @Synchronized
    fun registerSession(account: UtMailAccount, resources: MailSessionResources) {
        if (findById(account.id) !== account) throw MailOperationCancelledException()
        sessions[resources] = account
    }

    @Synchronized
    fun releaseSession(resources: MailSessionResources) {
        sessions.remove(resources)
    }

    private fun <T> withSessionRetirement(action: () -> T): T {
        check(!Thread.holdsLock(this)) { "Account mutations cannot be nested" }
        val retired = mutableListOf<MailSessionResources>()
        try {
            return synchronized(this) {
                try {
                    action()
                } finally {
                    val obsolete = sessions.filter { (resources, snapshot) ->
                        resources.isRetired || !sameAuthorization(snapshot, findById(snapshot.id))
                    }.keys
                    obsolete.forEach { it.retire() }
                    retired.addAll(obsolete)
                    obsolete.forEach { sessions.remove(it) }
                }
            }
        } finally {
            closeMailResources(retired)
        }
    }

    private fun retireAccountSessions(accountId: AccountId) {
        sessions.filterValues { it.id == accountId }.keys.forEach { it.retire() }
    }

    private fun sameAuthorization(old: UtMailAccount, current: UtMailAccount?): Boolean = current != null &&
        old.id == current.id && old.credentialKey == current.credentialKey &&
        old.incoming == current.incoming && old.outgoing == current.outgoing &&
        old.oauthProviderId == current.oauthProviderId && old.address == current.address

    private fun reload() {
        accounts.value = storage.all()
    }
}
