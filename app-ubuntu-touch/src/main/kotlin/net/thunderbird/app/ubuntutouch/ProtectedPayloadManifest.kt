package net.thunderbird.app.ubuntutouch

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

internal const val PROTECTED_CHUNK_BYTES = 1024
internal const val MAX_PROTECTED_PAYLOAD_BYTES = 40000
private const val MANIFEST_BYTES = 92
private const val MANIFEST_MAGIC = 0x55544348
private const val MANIFEST_VERSION = 1
private const val HASH_BYTES = 32
private const val INTERNAL_PREFIX = "ut-protected-v1:"

/** Contains a secret-derived digest: serialize only into protected storage, never SQLite or logs. */
internal class ProtectedPayloadManifest private constructor(
    val generation: UUID,
    val bytes: Int,
    private val rootBinding: ByteArray,
    private val digest: ByteArray,
) : AutoCloseable {
    init {
        require(bytes in 0..MAX_PROTECTED_PAYLOAD_BYTES)
        require(rootBinding.size == HASH_BYTES && digest.size == HASH_BYTES)
    }

    val chunks: Int get() = (bytes + PROTECTED_CHUNK_BYTES - 1) / PROTECTED_CHUNK_BYTES

    fun encode(): ByteArray = ByteBuffer.allocate(MANIFEST_BYTES).apply {
        putInt(MANIFEST_MAGIC)
        putInt(MANIFEST_VERSION)
        put(rootBinding)
        putLong(generation.mostSignificantBits)
        putLong(generation.leastSignificantBits)
        putInt(bytes)
        put(digest)
    }.array()

    fun verify(value: ByteArray) {
        val actual = payloadHash(value)
        try {
            check(value.size == bytes && MessageDigest.isEqual(digest, actual)) { "Invalid protected payload" }
        } finally {
            actual.fill(0)
        }
    }

    override fun toString(): String = "ProtectedPayloadManifest(redacted)"
    override fun close() {
        rootBinding.fill(0)
        digest.fill(0)
    }

    companion object {
        fun create(root: String, generation: UUID, value: ByteArray): ProtectedPayloadManifest {
            require(value.size <= MAX_PROTECTED_PAYLOAD_BYTES)
            return ProtectedPayloadManifest(generation, value.size, rootHash(root), payloadHash(value))
        }

        fun decode(root: String, encoded: ByteArray): ProtectedPayloadManifest {
            check(encoded.size == MANIFEST_BYTES) { "Invalid protected manifest" }
            val input = ByteBuffer.wrap(encoded)
            check(input.int == MANIFEST_MAGIC && input.int == MANIFEST_VERSION) { "Invalid protected manifest" }
            val binding = ByteArray(HASH_BYTES).also { input.get(it) }
            val expected = rootHash(root)
            val matches = MessageDigest.isEqual(binding, expected)
            expected.fill(0)
            if (!matches) {
                binding.fill(0)
                error("Invalid protected manifest")
            }
            val generation = UUID(input.long, input.long)
            val bytes = input.int
            if (bytes !in 0..MAX_PROTECTED_PAYLOAD_BYTES) {
                binding.fill(0)
                error("Invalid protected manifest")
            }
            val digest = ByteArray(HASH_BYTES).also { input.get(it) }
            return ProtectedPayloadManifest(generation, bytes, binding, digest)
        }
    }
}

internal fun protectedManifestKey(root: String): String = "$INTERNAL_PREFIX${rootAlias(root)}:manifest"

internal fun protectedChunkKey(root: String, generation: UUID, index: Int): String {
    require(index in 0 until (MAX_PROTECTED_PAYLOAD_BYTES + PROTECTED_CHUNK_BYTES - 1) / PROTECTED_CHUNK_BYTES)
    return "$INTERNAL_PREFIX${rootAlias(root)}:$generation:$index"
}

private fun rootAlias(root: String): String = rootHash(root).joinToString("") { "%02x".format(it) }

private fun rootHash(root: String): ByteArray {
    require(root.isNotBlank() && !root.startsWith(INTERNAL_PREFIX))
    return payloadHash(root.toByteArray(Charsets.UTF_8))
}

private fun payloadHash(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
