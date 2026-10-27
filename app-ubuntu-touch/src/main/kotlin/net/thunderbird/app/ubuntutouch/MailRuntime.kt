package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.K9MailLib
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import net.thunderbird.components.core.logging.Logger
import net.thunderbird.components.core.logging.ToggleableLogger
import net.thunderbird.legacy.logging.Log

/** Initialise the reused engine without enabling its historic message/address/protocol logging. */
internal fun initializeMailRuntime(
    logger: Logger,
    mimeDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "mail-mime"),
) {
    synchronized(BinaryTempFileBody::class.java) {
        val permissions = PosixFilePermissions.fromString("rwx------")
        Files.createDirectories(mimeDirectory, PosixFilePermissions.asFileAttribute(permissions))
        Files.setPosixFilePermissions(mimeDirectory, permissions)
        BinaryTempFileBody.setTempDirectory(mimeDirectory.toFile())
    }
    // Hide LoggingControl as well as disabling the delegate. App diagnostics retain their separate injected logger.
    Log.logger = object : Logger by ToggleableLogger(logger, false) {}
    K9MailLib.setDebugStatus(object : K9MailLib.DebugStatus {
        override fun enabled(): Boolean = false
        override fun debugSensitive(): Boolean = false
    })
}
