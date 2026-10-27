package net.thunderbird.app.ubuntutouch

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import net.thunderbird.feature.account.AccountId

/** Atomic private display preferences. Legacy global values default unconfigured views. IO callers only. */
internal class MailSortPreferences(private val path: Path) {
    fun read(context: MailSortContext? = null): MailSort {
        val destination = contextPath(context)
        val value = try {
            Files.newInputStream(destination).use {
                val bytes = it.readNBytes(MAX_VALUE_BYTES + 1)
                if (bytes.size > MAX_VALUE_BYTES) "" else bytes.toString(Charsets.UTF_8).trim()
            }
        } catch (_: NoSuchFileException) {
            return if (context == null) MailSort.NEWEST else read()
        }
        return MailSort.entries.singleOrNull { it.name == value } ?: MailSort.NEWEST
    }

    fun save(sort: MailSort, context: MailSortContext? = null) {
        val destination = contextPath(context)
        val temporary = Files.createTempFile(
            path.toAbsolutePath().parent,
            ".mail-sort-",
            ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
        try {
            Files.writeString(temporary, sort.name)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    private fun contextPath(context: MailSortContext?): Path {
        if (context == null) return path
        val identity = "${context.scope.name}\u0000${context.account.orEmptyId()}\u0000${context.folder.orEmpty()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val suffix = digest.joinToString("") { "%02x".format(it) }
        return path.resolveSibling("${path.fileName}.$suffix")
    }
}

private fun AccountId?.orEmptyId(): String = this?.toString().orEmpty()

/** Folder IDs and account identities stay opaque in diagnostics and preference filenames. */
internal data class MailSortContext(
    val scope: CachedSearchScope,
    val account: AccountId? = null,
    val folder: String? = null,
) {
    init {
        require(
            when (scope) {
                CachedSearchScope.FOLDER -> account != null && folder != null
                CachedSearchScope.ACCOUNT -> account != null && folder == null
                CachedSearchScope.ALL_ACCOUNTS -> account == null && folder == null
            },
        )
    }

    override fun toString(): String = "MailSortContext(redacted)"
}

internal fun LiveMailState.sortContext(): MailSortContext? {
    if (folder == null) return null
    return when (searchScope) {
        CachedSearchScope.FOLDER -> MailSortContext(searchScope, checkNotNull(account), folder.id)
        CachedSearchScope.ACCOUNT -> MailSortContext(searchScope, checkNotNull(account))
        CachedSearchScope.ALL_ACCOUNTS -> MailSortContext(searchScope)
    }
}

private const val MAX_VALUE_BYTES = 64
