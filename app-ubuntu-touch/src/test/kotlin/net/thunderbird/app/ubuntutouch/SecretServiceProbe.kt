package net.thunderbird.app.ubuntutouch

import java.util.UUID
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface

/** Explicit integration executable, excluded from the app; creates/removes only its randomly named test item. */
object SecretServiceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val lockFixture = args.contentEquals(arrayOf("--disposable-lock"))
        require(args.isEmpty() || lockFixture)
        if (lockFixture) check(System.getenv("MAIL_KEYRING_TEST_DISPOSABLE") == "1")
        val namespace = "mail-demo.thomasredstone.probe.${UUID.randomUUID()}"
        val testSubject = SecretServiceCredentialStore(namespace)
        val key = "roundtrip"
        try {
            check(testSubject.read(key) == null)
            testSubject.write(key, byteArrayOf(1, 2, 3, 0, -1))
            check(testSubject.read(key)!!.contentEquals(byteArrayOf(1, 2, 3, 0, -1)))
            check(SecretServiceCredentialStore("$namespace.other").read(key) == null)
            testSubject.write(key, byteArrayOf(7, 8, 9))
            check(testSubject.read(key)!!.contentEquals(byteArrayOf(7, 8, 9)))
            if (lockFixture) {
                lockDisposableCollection()
                val failure = runCatching { testSubject.read(key) }.exceptionOrNull() as SecretStoreException
                check(failure.reason == SecretFailure.LOCKED)
                println("Secret Service locked-item error passed on disposable keyring")
                return
            }
            testSubject.delete(key)
            check(testSubject.read(key) == null)
            println("Secret Service create/read/replace/delete and namespace isolation passed")
        } finally {
            if (!lockFixture) testSubject.delete(key)
        }
    }

    private fun lockDisposableCollection() {
        DBusConnectionBuilder.forSessionBus().withShared(false).build().use { connection ->
            val service = connection.getRemoteObject(
                "org.freedesktop.secrets",
                "/org/freedesktop/secrets",
                SecretService::class.java,
            )
            val lock = connection.getRemoteObject(
                "org.freedesktop.secrets",
                "/org/freedesktop/secrets",
                ProbeLockService::class.java,
            )
            val result = lock.lock(listOf(service.readAlias("default")))
            check(result.prompt.path == "/")
        }
    }
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Secret.Service")
internal interface ProbeLockService : DBusInterface {
    @DBusMemberName("Lock")
    fun lock(paths: List<DBusPath>): ProbeLockReply
}

internal class ProbeLockReply(
    @field:Position(0) val paths: List<DBusPath>,
    @field:Position(1) val prompt: DBusPath,
) : Tuple()
