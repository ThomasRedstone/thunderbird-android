package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Test-only entry point. Creates/deletes only this probe's fresh identities and temporary database. */
object SignonProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val directory = Files.createTempDirectory(Path.of(args.single()), "mail-signon-probe-")
        val key = "probe-${UUID.randomUUID()}"
        val otherKey = "probe-${UUID.randomUUID()}"
        try {
            val path = directory.resolve("mail.sqlite")
            MailDatabase(path).use { database ->
                val testSubject = SignonCredentialStore(SignonIdentityIndex(database))
                try {
                    check(testSubject.read(key) == null)
                    testSubject.write(key, byteArrayOf(1, 0, -1))
                    check(testSubject.read(key)!!.contentEquals(byteArrayOf(1, 0, -1)))
                    testSubject.write(otherKey, byteArrayOf(8))
                    testSubject.write(key, byteArrayOf(7, 6))
                    check(testSubject.read(key)!!.contentEquals(byteArrayOf(7, 6)))
                    testSubject.delete(key)
                    check(testSubject.read(key) == null)
                    check(testSubject.read(otherKey)!!.contentEquals(byteArrayOf(8)))
                    testSubject.delete(otherKey)
                    println("SignOn indexed create/read/replace/delete and identity isolation passed")
                } finally {
                    testSubject.delete(key)
                    testSubject.delete(otherKey)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
