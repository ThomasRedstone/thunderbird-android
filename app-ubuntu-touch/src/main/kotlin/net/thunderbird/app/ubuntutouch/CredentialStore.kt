package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.Variant

internal interface CredentialStore {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun delete(key: String)
}

internal enum class SecretFailure { UNAVAILABLE, LOCKED, PROMPT_REQUIRED, INVALID_REPLY }
internal class SecretStoreException(
    val reason: SecretFailure,
) : Exception("Protected credential storage: ${reason.name}")

/** The application attribute namespaces items; it is not protection from unconfined same-user processes. */
internal class SecretServiceCredentialStore(
    private val application: String = "mail-demo.thomasredstone",
) : CredentialStore {
    init {
        require(application.isNotBlank())
    }

    override fun read(key: String): ByteArray? = withSession { connection, service, session ->
        val items = search(service, key)
        if (items.isEmpty()) return@withSession null
        if (items.size != 1) throw SecretStoreException(SecretFailure.INVALID_REPLY)
        val secret = connection.getRemoteObject(SERVICE, items.single().path, SecretItem::class.java).getSecret(session)
        if (secret.session.path != session.path || secret.parameters.isNotEmpty()) {
            throw SecretStoreException(SecretFailure.INVALID_REPLY)
        }
        secret.value
    }

    override fun write(key: String, value: ByteArray) = withSession { connection, service, session ->
        search(service, key) // A locked item must never be mistaken for a missing item.
        val collection = service.readAlias("default")
        if (collection.path == "/") throw SecretStoreException(SecretFailure.UNAVAILABLE)
        val properties = mapOf(
            "org.freedesktop.Secret.Item.Label" to Variant("$application: account credentials"),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes(key), "a{ss}"),
        )
        val result = connection.getRemoteObject(SERVICE, collection.path, SecretCollection::class.java).createItem(
            properties,
            SecretValue(session, byteArrayOf(), value, "application/octet-stream"),
            true,
        )
        requireNoPrompt(result.prompt)
        if (result.item.path == "/") throw SecretStoreException(SecretFailure.INVALID_REPLY)
    }

    override fun delete(key: String) = withSession { connection, service, _ ->
        search(service, key).forEach { path ->
            requireNoPrompt(connection.getRemoteObject(SERVICE, path.path, SecretItem::class.java).delete())
        }
    }

    private fun attributes(key: String): Map<String, String> {
        require(key.isNotBlank())
        return mapOf("application" to application, "key" to key)
    }

    private fun search(service: SecretService, key: String): List<DBusPath> {
        val result = service.searchItems(attributes(key))
        if (result.locked.isNotEmpty()) throw SecretStoreException(SecretFailure.LOCKED)
        return result.unlocked
    }

    private fun requireNoPrompt(prompt: DBusPath) {
        if (prompt.path != "/") throw SecretStoreException(SecretFailure.PROMPT_REQUIRED)
    }

    private fun <T> withSession(block: (DBusConnection, SecretService, DBusPath) -> T): T {
        try {
            val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
            builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
            return builder.build().use { connection ->
                val service = connection.getRemoteObject(SERVICE, "/org/freedesktop/secrets", SecretService::class.java)
                val session = openSession(service)
                try {
                    block(connection, service, session)
                } finally {
                    // Closing the connection also releases sessions if the service has disappeared.
                    runCatching { connection.getRemoteObject(SERVICE, session.path, SecretSession::class.java).close() }
                }
            }
        } catch (exception: DBusExecutionException) {
            val reason = if (exception.type == "org.freedesktop.Secret.Error.IsLocked") {
                SecretFailure.LOCKED
            } else {
                SecretFailure.UNAVAILABLE
            }
            throw SecretStoreException(reason)
        } catch (_: DBusException) {
            throw SecretStoreException(SecretFailure.UNAVAILABLE)
        }
    }

    private fun openSession(service: SecretService): DBusPath {
        val session = service.openSession("plain", Variant("")).session
        if (session.path == "/") throw SecretStoreException(SecretFailure.INVALID_REPLY)
        return session
    }

    private companion object {
        const val SERVICE = "org.freedesktop.secrets"
    }
}
