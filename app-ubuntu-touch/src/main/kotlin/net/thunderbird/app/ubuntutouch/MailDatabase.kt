package net.thunderbird.app.ubuntutouch

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

private const val SIGNON_SCHEMA_VERSION = 3L
private const val OUTGOING_SCHEMA_VERSION = 4L
private const val CURRENT_SCHEMA_VERSION = 5L

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
            check(version in 0L..CURRENT_SCHEMA_VERSION) { "Unsupported mail database version" }
            if (version == 0L) createSchema()
            if (version < 2L) createAccountSchema()
            if (version < SIGNON_SCHEMA_VERSION) createSignonSchema()
            if (version < OUTGOING_SCHEMA_VERSION) createOutgoingSchema()
            if (version < CURRENT_SCHEMA_VERSION) createAttemptHistorySchema()
            // A process stopped before its SMTP result was committed. Never automatically resend these rows.
            execute("UPDATE outgoing SET state='UNCERTAIN',uncertain_attempt=1 WHERE state='SENDING'")
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

    private fun createAttemptHistorySchema() = transaction {
        execute("ALTER TABLE outgoing ADD COLUMN uncertain_attempt INTEGER NOT NULL DEFAULT 0")
        // Earlier previews did not preserve retry history. Require review before resubmitting their pending rows.
        execute("UPDATE outgoing SET uncertain_attempt=1 WHERE state IN ('QUEUED','FAILED','SENDING','UNCERTAIN')")
        execute("PRAGMA user_version=$CURRENT_SCHEMA_VERSION")
    }

    private fun createOutgoingSchema() = transaction {
        execute(
            """CREATE TABLE outgoing (
                id TEXT PRIMARY KEY NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                recipients TEXT NOT NULL, subject TEXT NOT NULL, body TEXT NOT NULL,
                message_id TEXT NOT NULL UNIQUE, state TEXT NOT NULL, mime BLOB)""",
        )
        execute("PRAGMA user_version=$OUTGOING_SCHEMA_VERSION")
    }

    private fun createSignonSchema() = transaction {
        execute("CREATE TABLE signon_identity_index (key TEXT PRIMARY KEY NOT NULL, identity_id INTEGER NOT NULL)")
        execute("PRAGMA user_version=$SIGNON_SCHEMA_VERSION")
    }

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
