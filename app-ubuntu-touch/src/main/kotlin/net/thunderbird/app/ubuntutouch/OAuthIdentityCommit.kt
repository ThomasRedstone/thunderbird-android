package net.thunderbird.app.ubuntutouch

import net.thunderbird.core.common.oauth.OAuthConfiguration

internal fun OAuthProviderRegistry.bindIdentity(
    account: UtMailAccount,
    tokens: OAuthTokens,
    verified: OAuthVerifiedIdentity?,
    previous: OAuthIdentityBinding? = null,
): OAuthTokens {
    val requirement = identityRequirementFor(account) ?: return tokens
    val proof = verified ?: throw OAuthIdentityException()
    proof.check(requirement, tokens)
    if (previous != null && !previous.sameSubject(proof.binding)) throw OAuthIdentityException()
    return tokens.withIdentity(proof.binding)
}

internal fun previousVerifiedIdentity(
    expected: UtMailAccount,
    replacement: UtMailAccount,
    configuration: OAuthConfiguration,
    providers: OAuthProviderRegistry,
    credentials: CredentialStore,
): OAuthIdentityBinding? = if (expected.oauthProviderId == replacement.oauthProviderId && expected.isOAuth &&
    providers.identityRequirementFor(replacement) != null
) {
    try {
        val requirement = checkNotNull(providers.identityRequirementFor(replacement)).copy(address = expected.address)
        OAuthTokenStore(credentials).read(OAuthGrant(expected.id, expected.credentialKey), configuration)?.identity
            ?.takeIf { it.matches(requirement) }
    } catch (_: OAuthTokenStorageException) {
        // A changed public registration requires fresh verification; legacy state cannot assert a subject.
        null
    }
} else {
    null
}
