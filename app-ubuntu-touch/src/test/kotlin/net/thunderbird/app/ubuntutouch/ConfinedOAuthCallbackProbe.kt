package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.thunderbird.core.common.oauth.OAuthConfiguration
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder

/** Synthetic installed-production callback test. Browser mode opens only the local callback, never a provider. */
object ConfinedOAuthCallbackProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[1] in setOf("socket", "browser"))
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "${args[0]} (enforce)")
        OAuthLoopbackReceiver.open(
            configuration = { redirect ->
                OAuthConfiguration(
                    "synthetic-client",
                    listOf("synthetic-scope"),
                    "https://login.example.invalid/authorize",
                    "https://login.example.invalid/token",
                    redirect.toASCIIString(),
                )
            },
            totalTimeoutMillis = PROBE_TIMEOUT,
        ).use { receiver ->
            val parameters = receiver.authorizationUri.rawQuery.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to URLDecoder.decode(pair[1], Charsets.UTF_8)
            }
            val redirect = URI(parameters.getValue("redirect_uri"))
            val callback = URI("$redirect?state=${parameters.getValue("state")}&code=synthetic-code")
            val pending = async { receiver.await() }
            withContext(Dispatchers.IO) {
                if (args[1] == "browser") dispatchLocalCallback(callback) else requestLocalCallback(callback)
            }
            val result = pending.await() as OAuthCallback.Authorized
            check(result.code == "synthetic-code" && result.redirectUri == redirect.toASCIIString())
            check(pkceChallenge(result.verifier) == parameters.getValue("code_challenge"))
        }
        println("Enforcing installed OAuth callback and PKCE (${args[1]}): PASS")
    }

    private fun dispatchLocalCallback(callback: URI) {
        val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
        builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
        builder.build().use { connection ->
            connection.getRemoteObject(
                "com.lomiri.URLDispatcher",
                "/com/lomiri/URLDispatcher",
                OAuthUrlDispatcher::class.java,
            ).dispatchUrl(callback.toASCIIString(), "")
        }
    }

    private fun requestLocalCallback(callback: URI) {
        Socket(callback.host, callback.port).use { socket ->
            socket.soTimeout = PROBE_TIMEOUT
            val request = "GET ${callback.rawPath}?${callback.rawQuery} HTTP/1.1\r\n" +
                "Host: ${callback.rawAuthority}\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            check(socket.getInputStream().readBytes().toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200 OK"))
        }
    }
}

private const val PROBE_TIMEOUT = 30_000
