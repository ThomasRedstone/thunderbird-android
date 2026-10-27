package net.thunderbird.app.ubuntutouch

import com.fsck.k9.mail.Message
import com.fsck.k9.mail.Multipart
import com.fsck.k9.mail.Part
import com.fsck.k9.mail.internet.BinaryTempFileBody
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap

/** The callback must return detached data, never the parsed Message/Part/Body or an open body stream. */
internal fun <T> PersistentBackendFolder.withCachedMessage(uid: String, read: (Message) -> T): T {
    val message = checkNotNull(readMessage(uid)) { "Message absent from cache" }
    return CachedMessageResources(message).use { read(it.message) }
}

/** Owns only a fresh cache parse. No externally supplied engine message is accepted by the public scope. */
private class CachedMessageResources(val message: Message) : AutoCloseable {
    override fun close() {
        val pending = ArrayDeque<Part>().apply { add(message) }
        val visited = Collections.newSetFromMap(IdentityHashMap<Part, Boolean>())
        var failure: IOException? = null
        while (pending.isNotEmpty()) {
            val part = pending.removeLast()
            if (!visited.add(part)) continue
            try {
                when (val body = part.body) {
                    is Multipart -> pending.addAll(body.bodyParts)

                    is Message -> pending.add(body)

                    // Decoder streams can intentionally retain this file. All consumers have finished here.
                    is BinaryTempFileBody -> body.file?.toPath()?.let { Files.deleteIfExists(it) }
                }
            } catch (exception: IOException) {
                if (failure == null) failure = exception else failure.addSuppressed(exception)
            }
        }
        failure?.let { throw IllegalStateException("Unable to release cached message resources", it) }
    }
}

/** Own a fresh parse of an immutable cached snapshot, returning detached data only. */
internal fun <T> withParsedCachedMime(raw: ByteArray, read: (Message) -> T): T {
    val message = com.fsck.k9.mail.internet.MimeMessage.parseMimeMessage(raw.inputStream(), true)
    return CachedMessageResources(message).use { read(it.message) }
}
