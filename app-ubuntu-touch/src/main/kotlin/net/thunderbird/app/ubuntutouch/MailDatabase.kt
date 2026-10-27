package net.thunderbird.app.ubuntutouch

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

private const val SIGNON_SCHEMA_VERSION = 3L
private const val OUTGOING_SCHEMA_VERSION = 4L
private const val ATTEMPT_SCHEMA_VERSION = 5L
private const val RECIPIENT_SCHEMA_VERSION = 6L
private const val FLAG_SCHEMA_VERSION = 7L
private const val THREAD_SCHEMA_VERSION = 8L
private const val SENT_SCHEMA_VERSION = 9L
private const val OAUTH_SCHEMA_VERSION = 10L
private const val SIGNATURE_SCHEMA_VERSION = 11L
private const val CURRENT_SCHEMA_VERSION = 12L

/** One owner serialises access; callers run blocking backend operations on Dispatchers.IO. */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions") // Versioned migrations; roll back every failure unchanged.
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
            if (version < ATTEMPT_SCHEMA_VERSION) createAttemptHistorySchema()
            if (version < RECIPIENT_SCHEMA_VERSION) createRecipientSchema()
            if (version < FLAG_SCHEMA_VERSION) createPendingFlagsSchema()
            if (version < THREAD_SCHEMA_VERSION) createThreadingSchema()
            if (version < SENT_SCHEMA_VERSION) createSentSchema()
            if (version < OAUTH_SCHEMA_VERSION) createOAuthAccountSchema()
            if (version < SIGNATURE_SCHEMA_VERSION) createSignatureSchema()
            if (version < CURRENT_SCHEMA_VERSION) createMoveSchema()
            // A process stopped before its SMTP result was committed. Never automatically resend these rows.
            execute("UPDATE outgoing SET state='UNCERTAIN',uncertain_attempt=1 WHERE state='SENDING'")
            execute("UPDATE sent_copies SET state='UNKNOWN',uncertain_attempt=1 WHERE state='WORKING'")
            execute("UPDATE mail_moves SET state='UNKNOWN',uncertain=1 WHERE state='WORKING'")
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

    private fun createMoveSchema() = transaction {
        execute(
            """CREATE TABLE IF NOT EXISTS mail_moves (
                id TEXT PRIMARY KEY NOT NULL, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                source TEXT NOT NULL, uid TEXT NOT NULL, target TEXT NOT NULL,
                source_validity INTEGER NOT NULL, target_validity INTEGER NOT NULL,
                state TEXT NOT NULL, attempt INTEGER NOT NULL DEFAULT 0, uncertain INTEGER NOT NULL DEFAULT 0)""",
        )
        execute(
            """CREATE UNIQUE INDEX IF NOT EXISTS one_pending_move ON mail_moves(account,source,uid)
                WHERE state!='COMPLETE'""",
        )
        execute("PRAGMA user_version=$CURRENT_SCHEMA_VERSION")
    }

    private fun createSignatureSchema() = transaction {
        execute(
            """CREATE TABLE IF NOT EXISTS signatures (
                account TEXT PRIMARY KEY NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                text TEXT NOT NULL, enabled INTEGER NOT NULL, replies INTEGER NOT NULL, forwards INTEGER NOT NULL)""",
        )
        execute("PRAGMA user_version=$SIGNATURE_SCHEMA_VERSION")
    }

    private fun createOAuthAccountSchema() = transaction {
        execute("ALTER TABLE accounts ADD COLUMN oauth_provider TEXT")
        execute("PRAGMA user_version=$OAUTH_SCHEMA_VERSION")
    }

    private fun createSentSchema() = transaction {
        execute(
            """CREATE TABLE sent_settings (
                account TEXT PRIMARY KEY NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, folder TEXT)""",
        )
        execute(
            """CREATE TABLE sent_copies (
                outgoing TEXT PRIMARY KEY NOT NULL REFERENCES outgoing(id) ON DELETE CASCADE,
                account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, folder TEXT NOT NULL,
                state TEXT NOT NULL, uid TEXT, uncertain_attempt INTEGER NOT NULL DEFAULT 0)""",
        )
        execute("PRAGMA user_version=$SENT_SCHEMA_VERSION")
    }

    private fun createThreadingSchema() = transaction {
        execute("ALTER TABLE outgoing ADD COLUMN in_reply_to TEXT NOT NULL DEFAULT ''")
        execute("ALTER TABLE outgoing ADD COLUMN thread_references TEXT NOT NULL DEFAULT ''")
        execute("PRAGMA user_version=$THREAD_SCHEMA_VERSION")
    }

    private fun createPendingFlagsSchema() = transaction {
        execute(
            """CREATE TABLE pending_flags (
                id INTEGER PRIMARY KEY AUTOINCREMENT, account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                folder INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE, uid TEXT NOT NULL,
                flag TEXT NOT NULL, enabled INTEGER NOT NULL, uid_validity INTEGER NOT NULL,
                UNIQUE(account,folder,uid,flag))""",
        )
        execute("PRAGMA user_version=$FLAG_SCHEMA_VERSION")
    }

    private fun createRecipientSchema() = transaction {
        execute("ALTER TABLE outgoing ADD COLUMN cc TEXT NOT NULL DEFAULT ''")
        execute("ALTER TABLE outgoing ADD COLUMN bcc TEXT NOT NULL DEFAULT ''")
        execute("PRAGMA user_version=$RECIPIENT_SCHEMA_VERSION")
    }

    private fun createAttemptHistorySchema() = transaction {
        execute("ALTER TABLE outgoing ADD COLUMN uncertain_attempt INTEGER NOT NULL DEFAULT 0")
        // Earlier previews did not preserve retry history. Require review before resubmitting their pending rows.
        execute("UPDATE outgoing SET uncertain_attempt=1 WHERE state IN ('QUEUED','FAILED','SENDING','UNCERTAIN')")
        execute("PRAGMA user_version=$ATTEMPT_SCHEMA_VERSION")
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
