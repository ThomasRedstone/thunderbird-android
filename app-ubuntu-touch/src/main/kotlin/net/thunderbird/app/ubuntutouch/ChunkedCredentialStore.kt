package net.thunderbird.app.ubuntutouch

/** Single owner; the account manager must serialize replacement, refresh and removal around this store. */
internal class ChunkedCredentialStore(
    private val protected: CredentialStore,
    private val journal: ProtectedPayloadJournal,
) : CredentialStore {
    @Synchronized
    override fun read(key: String): ByteArray? {
        protectedManifestKey(key)
        if (journal.isDeleting(key)) throw SecretStoreException(SecretFailure.UNAVAILABLE)
        val manifest = manifest(key)
        if (manifest == null) {
            if (journal.contains(key)) throw SecretStoreException(SecretFailure.INVALID_REPLY)
            return protected.read(key)
        }
        return manifest.use { readChunks(key, it) }
    }

    @Synchronized
    override fun write(key: String, value: ByteArray) {
        protectedManifestKey(key)
        require(value.size <= MAX_PROTECTED_PAYLOAD_BYTES)
        if (journal.isDeleting(key)) throw SecretStoreException(SecretFailure.UNAVAILABLE)
        // Never overwrite an unreadable/corrupt pointer. A confirmed absent pointer can be replaced.
        manifest(key)?.close()
        val generation = journal.begin(key, value.size)
        repeat(generation.chunks) { index ->
            val start = index * PROTECTED_CHUNK_BYTES
            val chunk = value.copyOfRange(start, minOf(start + PROTECTED_CHUNK_BYTES, value.size))
            try {
                protected.write(protectedChunkKey(key, generation.id, index), chunk)
            } finally {
                chunk.fill(0)
            }
        }
        ProtectedPayloadManifest.create(key, generation.id, value).use { expected ->
            val encoded = expected.encode()
            try {
                // Lost acknowledgement is propagated, never replayed or converted to apparent success.
                journal.markPointerAttempt(key)
                protected.write(protectedManifestKey(key), encoded)
                manifest(key).use { actual ->
                    if (actual == null || actual.generation != generation.id || actual.bytes != value.size) {
                        throw SecretStoreException(SecretFailure.INVALID_REPLY)
                    }
                    validate { actual.verify(value) }
                }
            } finally {
                encoded.fill(0)
            }
        }
        // Committed replacement stays successful if only retirement is temporarily unavailable.
        try {
            cleanupRoot(key)
        } catch (_: SecretStoreException) {
            // Durable cleanup_pending remains visible for explicit retry.
        }
    }

    @Synchronized
    override fun delete(key: String) {
        journal.markDeleting(key)
        protected.delete(protectedManifestKey(key))
        protected.delete(key)
        journal.generations(key).forEach { deleteGeneration(key, it) }
        journal.finishDelete(key)
    }

    @Synchronized
    override fun hasPendingCleanup(): Boolean = journal.pendingRoots().isNotEmpty()

    @Synchronized
    override fun cleanupPending(): Boolean {
        try {
            journal.pendingRoots().forEach { root ->
                if (journal.isDeleting(root)) delete(root) else cleanupRoot(root)
            }
        } catch (_: SecretStoreException) {
            return false
        }
        return true
    }

    private fun cleanupRoot(key: String) {
        manifest(key).use { active ->
            if (active == null && journal.pointerAttempted(key)) {
                throw SecretStoreException(SecretFailure.INVALID_REPLY)
            }
            journal.generations(key).filter { it.id != active?.generation }.forEach { deleteGeneration(key, it) }
            if (active == null) {
                journal.abandon(key)
            } else {
                protected.delete(key) // Retire legacy bytes only after the protected pointer is readable.
                journal.markClean(key)
            }
        }
    }

    private fun deleteGeneration(key: String, generation: ProtectedPayloadGeneration) {
        repeat(generation.chunks) { protected.delete(protectedChunkKey(key, generation.id, it)) }
        journal.forget(key, generation)
    }

    private fun manifest(key: String): ProtectedPayloadManifest? {
        val encoded = protected.read(protectedManifestKey(key)) ?: return null
        try {
            val decoded = validate { ProtectedPayloadManifest.decode(key, encoded) }
            val owned = journal.generations(key).any { it.id == decoded.generation && it.chunks == decoded.chunks }
            if (!owned) {
                decoded.close()
                throw SecretStoreException(SecretFailure.INVALID_REPLY)
            }
            return decoded
        } finally {
            encoded.fill(0)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Zero assembled secrets on every failure before returning detached bytes.
    private fun readChunks(key: String, manifest: ProtectedPayloadManifest): ByteArray {
        val value = ByteArray(manifest.bytes)
        try {
            repeat(manifest.chunks) { index ->
                val chunk = readChunk(key, manifest, index)
                try {
                    val offset = index * PROTECTED_CHUNK_BYTES
                    chunk.copyInto(value, offset)
                } finally {
                    chunk.fill(0)
                }
            }
            validate { manifest.verify(value) }
            return value
        } catch (failure: Exception) {
            value.fill(0)
            throw failure
        }
    }

    private fun readChunk(key: String, manifest: ProtectedPayloadManifest, index: Int): ByteArray {
        val chunk = protected.read(protectedChunkKey(key, manifest.generation, index))
            ?: throw SecretStoreException(SecretFailure.INVALID_REPLY)
        if (chunk.size != minOf(PROTECTED_CHUNK_BYTES, manifest.bytes - index * PROTECTED_CHUNK_BYTES)) {
            chunk.fill(0)
            throw SecretStoreException(SecretFailure.INVALID_REPLY)
        }
        return chunk
    }

    private fun <T> validate(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw SecretStoreException(SecretFailure.INVALID_REPLY)
    } catch (_: IllegalStateException) {
        throw SecretStoreException(SecretFailure.INVALID_REPLY)
    }
}
