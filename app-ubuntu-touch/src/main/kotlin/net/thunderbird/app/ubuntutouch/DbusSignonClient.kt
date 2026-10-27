package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.util.Base64
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

internal class DbusSignonClient : SignonClient {
    private val connection: DBusConnection = checked {
        val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
        builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
        builder.build()
    }
    private val service: SignonService = connection.getRemoteObject(
        SIGNON_SERVICE,
        SIGNON_PATH,
        SignonService::class.java,
    )

    override fun exists(id: Long, key: String): Boolean = checked { identity(id, key) != null }

    override fun reserve(key: String): Long = checked {
        val path = service.registerNewIdentity("")
        if (path.path == "/") throw SecretStoreException(SecretFailure.INVALID_REPLY)
        val identity = connection.getRemoteObject(SIGNON_SERVICE, path.path, SignonIdentity::class.java)
        // SignOn stores only metadata here. Persist the returned ID before the second store contains a secret.
        identity.store(metadata(0, key, null)).toLong()
    }

    override fun write(id: Long, key: String, value: ByteArray) = checked {
        val identity = identity(id, key) ?: throw SecretStoreException(SecretFailure.UNAVAILABLE)
        val encoded = PAYLOAD_PREFIX + Base64.getEncoder().encodeToString(value)
        val storedId = identity.store(metadata(id, key, encoded)).toLong()
        if (storedId != id) throw SecretStoreException(SecretFailure.INVALID_REPLY)
    }

    override fun read(id: Long, key: String): ByteArray? = checked {
        if (identity(id, key) == null) return@checked null
        val path = service.getAuthSessionObjectPath(UInt32(id), "", "password")
        val session = connection.getRemoteObject(SIGNON_SERVICE, path.path, SignonAuthSession::class.java)
        try {
            val response = session.process(mapOf("UiPolicy" to Variant(NO_USER_INTERACTION)), "password")
            val secret = response["Secret"]?.value as? String
                ?: throw SecretStoreException(SecretFailure.INVALID_REPLY)
            if (!secret.startsWith(PAYLOAD_PREFIX)) throw SecretStoreException(SecretFailure.INVALID_REPLY)
            try {
                Base64.getDecoder().decode(secret.removePrefix(PAYLOAD_PREFIX))
            } catch (_: IllegalArgumentException) {
                throw SecretStoreException(SecretFailure.INVALID_REPLY)
            }
        } finally {
            runCatching { session.objectUnref() }
        }
    }

    override fun delete(id: Long, key: String) = checked { identity(id, key)?.remove() ?: Unit }
    override fun close() = connection.close()

    private fun identity(id: Long, key: String): SignonIdentity? {
        val reply = try {
            service.getIdentity(UInt32(id), "")
        } catch (failure: DBusExecutionException) {
            if (failure.type == "$SIGNON_SERVICE.Error.IdentityNotFound") return null
            throw failure
        }
        if (reply.data["UserName"]?.value != namespace(key)) throw SecretStoreException(SecretFailure.INVALID_REPLY)
        return connection.getRemoteObject(SIGNON_SERVICE, reply.path.path, SignonIdentity::class.java)
    }

    private fun metadata(id: Long, key: String, secret: String?): Map<String, Variant<*>> = buildMap {
        put("Id", Variant(UInt32(id)))
        put("UserName", Variant(namespace(key)))
        put("Caption", Variant("Mail demo account credentials"))
        put("AuthMethods", Variant(mapOf("password" to listOf("password")), "a{sas}"))
        put("StoreSecret", Variant(secret != null))
        if (secret != null) put("Secret", Variant(secret))
    }

    private fun namespace(key: String): String = "mail-demo.thomasredstone:$key"

    private fun <T> checked(operation: () -> T): T {
        try {
            return operation()
        } catch (failure: DBusExecutionException) {
            val reason = if (failure.type.endsWith(".UserInteraction")) {
                SecretFailure.PROMPT_REQUIRED
            } else {
                SecretFailure.UNAVAILABLE
            }
            throw SecretStoreException(reason)
        } catch (_: DBusException) {
            throw SecretStoreException(SecretFailure.UNAVAILABLE)
        }
    }

    private companion object {
        const val NO_USER_INTERACTION = 2
        const val PAYLOAD_PREFIX = "ut-mail-v1:"
    }
}
