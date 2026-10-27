package net.thunderbird.app.ubuntutouch

import com.sun.security.auth.module.UnixSystem
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** Diagnose only synthetic owned credentials. Never print reply bodies or exception messages. */
object ConfinedSignonSizeProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0] == PROFILE)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(label == "$PROFILE (enforce)")
        val parent = Path.of(System.getProperty("user.home"), ".cache", "mail-demo.thomasredstone", "probes")
        val directory = Files.createTempDirectory(parent, "oauth-identity-runtime-size-")
        var cleaned = false
        try {
            cleaned = MailDatabase(directory.resolve("mail.sqlite")).use(::exercise)
        } finally {
            if (cleaned) {
                listOf("mail.sqlite", "mail.sqlite-wal", "mail.sqlite-shm").forEach {
                    Files.deleteIfExists(directory.resolve(it))
                }
                Files.delete(directory)
            } else {
                println("Size fixture retained for protected cleanup: ${directory.fileName}")
            }
        }
    }

    private fun exercise(database: MailDatabase): Boolean {
        val index = SignonIdentityIndex(database)
        val raw = SignonCredentialStore(index)
        try {
            for (size in listOf(1024, 3072, 8192, 16384, 24576, 39997)) {
                val key = UUID.randomUUID().toString()
                try {
                    raw.write(key, ByteArray(size) { 'A'.code.toByte() })
                    inspect(checkNotNull(index.find(key)), size)
                } finally {
                    raw.delete(key)
                }
            }
        } finally {
            database.query("SELECT key FROM signon_identity_index") { getText(0) }.forEach(raw::delete)
        }
        return database.query("SELECT key FROM signon_identity_index") { getText(0) }.isEmpty()
    }

    private fun inspect(id: Long, size: Int) {
        val builder = DBusConnectionBuilder.forSessionBus().withShared(false)
        builder.transportConfig().configureSasl().withSaslUid(UnixSystem().uid)
        builder.build().use { connection ->
            val service = connection.getRemoteObject(SIGNON_SERVICE, SIGNON_PATH, SignonService::class.java)
            val path = service.getAuthSessionObjectPath(UInt32(id), "", "password")
            val session = connection.getRemoteObject(SIGNON_SERVICE, path.path, SignonAuthSession::class.java)
            try {
                val reply = session.process(mapOf("UiPolicy" to Variant(2)), "password")
                val value = reply["Secret"]?.value as? String
                check(value != null)
                println("SIGNON_SIZE raw=$size readable=true encodedCharacters=${value.length}")
            } catch (failure: DBusExecutionException) {
                val type =
                    failure.type.takeIf {
                        it.length <= 256 &&
                            it.all { char -> char.isLetterOrDigit() || char == '.' || char == '_' }
                    }
                        ?: "REDACTED"
                println("SIGNON_SIZE raw=$size readable=false errorType=$type")
            } finally {
                runCatching { session.objectUnref() }
            }
        }
    }

    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.25"
}
