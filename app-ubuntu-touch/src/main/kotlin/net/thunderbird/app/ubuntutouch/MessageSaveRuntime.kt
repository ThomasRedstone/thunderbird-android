package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** One application-owned transfer model and connection, independent of mailbox navigation. Construct on IO. */
internal class MessageSaveRuntime(
    database: MailDatabase,
    accounts: UtAccountManager,
    data: Path,
    appId: String,
) : AutoCloseable {
    private val exports = privateExportDirectory(data.resolve("message-exports"))
    private val broker = DbusContentExportBroker(appId, exports)
    val model = MessageSaveViewModel(
        ContentExportController(
            CachedMessageExport(database, exports),
            ContentExportJournal(data.resolve("message-export-journal"), exports),
            broker,
            { accounts.findById(it) != null },
        ),
        accounts,
        changes = broker.changes,
    )

    override fun close() {
        model.close()
        CoroutineScope(Dispatchers.IO).launch { broker.close() }
    }
}

internal fun privateExportDirectory(path: Path): Path {
    require(path.isAbsolute && path.normalize() == path)
    val permissions = PosixFilePermissions.fromString("rwx------")
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(permissions))
    check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
    Files.setPosixFilePermissions(path, permissions)
    return path
}
