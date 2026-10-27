package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import java.util.UUID
import net.thunderbird.feature.account.AccountIdFactory
import org.junit.Test

class OAuthGrantTest {
    private val account = AccountIdFactory.create()

    @Test
    fun `reauthorizing same account creates new credential generation while references can reopen exactly`() {
        val testSubject = OAuthGrant(account)
        val replacement = OAuthGrant(account)
        assertThat(testSubject.accountId).isEqualTo(replacement.accountId)
        assertThat(testSubject.credentialKey).isNotEqualTo(replacement.credentialKey)
        assertThat(OAuthGrant(account, testSubject.credentialKey)).isEqualTo(testSubject)
        assertThat(testSubject.toString()).isEqualTo("OAuthGrant(redacted)")
    }

    @Test
    fun `legacy password keys malformed references and keys for another account are rejected`() {
        val valid = OAuthGrant(account)
        listOf(
            UUID.randomUUID().toString(),
            "ut-oauth-v1:$account",
            "ut-oauth-v2:$account:not-a-generation",
            "ut-oauth-v2:$account:00000000-0000-0000-0000-00000000000g",
            "x".repeat(10000),
            OAuthGrant(AccountIdFactory.create()).credentialKey,
        ).forEach { key ->
            assertThat(runCatching { OAuthGrant(account, key) }.exceptionOrNull() is IllegalArgumentException).isTrue()
        }
        assertThat(valid.credentialKey.startsWith("ut-oauth-v2:")).isTrue()
    }
}
