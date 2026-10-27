package net.thunderbird.app.ubuntutouch

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

/** Only run after a verified private pre-upgrade backup and a normal app close. No account content is printed. */
object ConfinedProductionMigrationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val label = Files.readString(Path.of("/proc/self/attr/current")).trim().trimEnd('\u0000')
        check(args[0] == PROFILE && label == "$PROFILE (enforce)") { "Required application confinement unavailable" }
        require(Regex("[a-f0-9]{40}").matches(args[1]))
        val installed = Path.of("/opt/click.ubuntu.com/mail-demo.thomasredstone/current/mail-demo")
        check(Files.readString(installed.resolve("SOURCE-COMMIT.txt")).trim() == args[1])
        check(Files.size(installed.resolve("SOURCE-CHANGES.patch")) == 0L)
        val path = mailDatabasePath()
        val expected = Path.of(
            System.getProperty("user.home"),
            ".local",
            "share",
            "mail-demo.thomasredstone",
            "mail.sqlite",
        )
        check(path == expected) { "Unexpected production data directory" }
        val before = if (Files.exists(path)) {
            BundledSQLiteDriver().open(path.toString()).use { connection ->
                connection.prepare("PRAGMA user_version").use { statement ->
                    check(statement.step())
                    statement.getLong(0)
                }
            }
        } else {
            0L
        }
        MailDatabase(path).use { database ->
            check(database.query("PRAGMA quick_check") { getText(0) } == listOf("ok"))
            check(database.query("PRAGMA user_version") { getLong(0) }.single() == CURRENT_SCHEMA)
        }
        println("Enforcing production storage initialization/migration $before->$CURRENT_SCHEMA and quick_check: PASS")
    }

    private const val PROFILE = "mail-demo.thomasredstone_mail-demo_0.1.31"
    private const val CURRENT_SCHEMA = 11L
}
