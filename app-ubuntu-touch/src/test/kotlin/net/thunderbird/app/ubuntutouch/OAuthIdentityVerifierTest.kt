package net.thunderbird.app.ubuntutouch

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OAuthIdentityVerifierTest {
    private val policy = OAuthIdentityPolicy("https://login.example.invalid/profile")
    private val tokens = OAuthTokens("private-access", "private-refresh", 1)
    private val valid = """{"sub":"opaque-subject","email":"fixture@example.invalid","email_verified":true}"""

    @Test
    fun `quoted verification flag is not a JSON boolean`() = runBlocking {
        val bytes = valid.replace("true", "\"true\"").toByteArray()
        val testSubject = HttpsOAuthIdentityVerifier(
            OAuthIdentityHttp { _, _ ->
                OAuthHttpReply(200, "application/json", bytes)
            },
        )
        assertThat(
            runCatching {
                testSubject.verify(policy, tokens)
            }.exceptionOrNull() is OAuthIdentityException,
        ).isTrue()
    }

    @Test
    fun `profile proof binds endpoint email and exact access token and clears response bytes`() = runBlocking {
        val bytes = valid.toByteArray()
        val testSubject = HttpsOAuthIdentityVerifier(
            OAuthIdentityHttp { endpoint, access ->
                assertThat(endpoint.toASCIIString()).isEqualTo(policy.endpoint)
                assertThat(access).isEqualTo(tokens.accessToken)
                OAuthHttpReply(200, "application/json; charset=utf-8", bytes)
            },
        )
        val verified = testSubject.verify(policy, tokens)
        val requirement = OAuthIdentityRequirement(policy, "fixture@example.invalid")
        verified.check(requirement, tokens)
        assertThat(verified.binding.subject).isEqualTo("opaque-subject")
        assertThat(bytes.all { it == 0.toByte() }).isTrue()
        listOf(
            requirement.copy(address = "other@example.invalid"),
            requirement.copy(policy = OAuthIdentityPolicy("https://other.example.invalid/profile")),
        ).forEach { assertThat(runCatching { verified.check(it, tokens) }.isFailure).isTrue() }
        assertThat(
            runCatching {
                verified.check(requirement, OAuthTokens("different", "refresh", 1))
            }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                verified.check(requirement, OAuthTokens(tokens.accessToken, "different-refresh", 1))
            }.isFailure,
        ).isTrue()
        assertThat(verified.toString()).isEqualTo("OAuthVerifiedIdentity(redacted)")
        assertThat(verified.binding.toString()).isEqualTo("OAuthIdentityBinding(redacted)")
    }

    @Test
    fun `unverified missing malformed duplicate and mistyped claims fail closed and clear bytes`() = runBlocking {
        val invalid = listOf(
            valid.replace("true", "false"), valid.replace("true", "\"true\""),
            valid.replace("true", "null"), valid.replace("opaque-subject", ""),
            valid.replace("opaque-subject", "s".repeat(256)),
            valid.replace("fixture@example.invalid", "bad\\naddress"),
            """{"sub":"subject","email":"fixture@example.invalid"}""",
            valid.dropLast(1) + ",\"em\\u0061il\":\"other@example.invalid\"}",
            valid.dropLast(1) + ",\"email_verified\":false}",
            "[]", "not JSON",
        )
        for (input in invalid) {
            val bytes = input.toByteArray()
            val testSubject = HttpsOAuthIdentityVerifier(
                OAuthIdentityHttp { _, _ ->
                    OAuthHttpReply(200, "application/json", bytes)
                },
            )
            assertThat(
                runCatching {
                    testSubject.verify(policy, tokens)
                }.exceptionOrNull() is OAuthIdentityException,
            ).isTrue()
            assertThat(bytes.all { it == 0.toByte() }).isTrue()
        }
    }

    @Test
    fun `redirect error non JSON oversized and invalid UTF8 profiles never become identity proofs`() = runBlocking {
        val replies = listOf(
            OAuthHttpReply(302, "application/json", valid.toByteArray()),
            OAuthHttpReply(401, "application/json", valid.toByteArray()),
            OAuthHttpReply(200, "text/html", valid.toByteArray()),
            OAuthHttpReply(200, "application/json", ByteArray(MAX_TOKEN_RESPONSE + 1)),
            OAuthHttpReply(200, "application/json", byteArrayOf(0xc3.toByte(), 0x28)),
        )
        for (reply in replies) {
            val testSubject = HttpsOAuthIdentityVerifier(OAuthIdentityHttp { _, _ -> reply })
            assertThat(
                runCatching {
                    testSubject.verify(policy, tokens)
                }.exceptionOrNull() is OAuthIdentityException,
            ).isTrue()
            assertThat(reply.body.all { it == 0.toByte() }).isTrue()
        }
    }
}
