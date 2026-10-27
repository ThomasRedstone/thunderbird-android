package com.fsck.k9.mail.ssl

import java.net.Socket

/** Optional connection ownership hook, including the unencrypted phase before a required STARTTLS upgrade. */
interface PlainSocketFactory {
    fun createPlainSocket(): Socket
}

/** Existing TLS factories keep their original plain-socket behaviour unless they opt into ownership. */
fun TrustedSocketFactory.createPlainSocket(): Socket =
    (this as? PlainSocketFactory)?.createPlainSocket() ?: Socket()
