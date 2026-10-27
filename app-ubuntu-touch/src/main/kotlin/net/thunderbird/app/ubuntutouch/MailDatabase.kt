package net.thunderbird.app.ubuntutouch

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

/** One owner serialises access; callers run blocking backend operations on Dispatchers.IO. */
@Suppress("TooGenericExceptionCaught") // Always close/roll back on failure, including cancellation; rethrow unchanged.
internal class MailDatabase(path: Path) : AutoCloseable {
    private val connection: SQLiteConnection

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        connection = BundledSQLiteDriver().open(path.toAbsolutePath().toString())
        try {
            execute("PRAGMA foreign_keys=ON")
            execute("PRAGMA busy_timeout=5000")
            val version = query("PRAGMA user_version") { getLong(0) }.single()
            check(version in 0L..2L) { "Unsupported mail database version" }
            if (version == 0L) createSchema()
            if (version < 2L) createAccountSchema()
        } catch (exception: Exception) {
            connection.close()
            throw exception
        }
    }

    @Synchronized
    fun execute(sql: String, vararg values: Any?) {
        connection.prepare(sql).use { statement ->
            statement.bind(values)
            statement.step()
        }
    }

    @Synchronized
    fun <T> query(sql: String, vararg values: Any?, read: SQLiteStatement.() -> T): List<T> =
        connection.prepare(sql).use { statement ->
            statement.bind(values)
            buildList { while (statement.step()) add(statement.read()) }
        }

    @Synchronized
    fun <T> transaction(block: () -> T): T {
        execute("BEGIN IMMEDIATE")
        try {
            val result = block()
            execute("COMMIT")
            return result
        } catch (exception: Throwable) {
            try {
                execute("ROLLBACK")
            } catch (rollbackFailure: Exception) {
                exception.addSuppressed(rollbackFailure)
            }
            throw exception
        }
    }

    @Synchronized
    override fun close() = connection.close()

    private fun createAccountSchema() = transaction {
        execute(
            """CREATE TABLE accounts (
                id TEXT PRIMARY KEY NOT NULL, display_name TEXT NOT NULL, address TEXT NOT NULL,
                credential_key TEXT NOT NULL, position INTEGER NOT NULL)""",
        )
        execute(
            """CREATE TABLE servers (
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, kind TEXT NOT NULL,
                host TEXT NOT NULL, port INTEGER NOT NULL, security TEXT NOT NULL, auth TEXT NOT NULL,
                username TEXT NOT NULL, PRIMARY KEY(account,kind))""",
        )
        execute("PRAGMA user_version=2")
        execute("CREATE TABLE credential_cleanup (key TEXT PRIMARY KEY NOT NULL)")
    }

    private fun createSchema() = transaction {
        execute(
            """CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account TEXT NOT NULL, server_id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL,
                UNIQUE(account, server_id))""",
        )
        execute(
            """CREATE TABLE messages (
                folder INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                uid TEXT NOT NULL, mime BLOB NOT NULL, effective_date INTEGER,
                PRIMARY KEY(folder, uid))""",
        )
        execute(
            """CREATE TABLE flags (
                folder INTEGER NOT NULL, uid TEXT NOT NULL, flag TEXT NOT NULL,
                PRIMARY KEY(folder, uid, flag),
                FOREIGN KEY(folder, uid) REFERENCES messages(folder, uid) ON DELETE CASCADE)""",
        )
        execute(
            """CREATE TABLE metadata (
                account TEXT NOT NULL, folder INTEGER NOT NULL, name TEXT NOT NULL,
                text_value TEXT, number_value INTEGER, PRIMARY KEY(account, folder, name))""",
        )
        execute("PRAGMA user_version=1")
    }
}

private fun SQLiteStatement.bind(values: Array<out Any?>) {
    values.forEachIndexed { index, value ->
        val parameter = index + 1
        when (value) {
            null -> bindNull(parameter)
            is String -> bindText(parameter, value)
            is Long -> bindLong(parameter, value)
            is Int -> bindLong(parameter, value.toLong())
            is ByteArray -> bindBlob(parameter, value)
            else -> error("Unsupported database parameter type")
        }
    }
}
