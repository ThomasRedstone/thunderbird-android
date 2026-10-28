package net.thunderbird.app.ubuntutouch

import java.util.UUID

internal data class ProtectedPayloadGeneration(val id: UUID, val chunks: Int) {
    override fun toString(): String = "ProtectedPayloadGeneration(redacted)"
}

/** Ownership only. No secret bytes or secret-derived digests enter this database. */
@Suppress("TooManyFunctions") // Explicit durable lifecycle steps keep secret operations outside SQLite transactions.
internal class ProtectedPayloadJournal(private val database: MailDatabase) {
    fun begin(root: String, bytes: Int): ProtectedPayloadGeneration = database.transaction {
        protectedManifestKey(root)
        require(bytes in 0..MAX_PROTECTED_PAYLOAD_BYTES)
        check(!isDeleting(root)) { "Protected deletion pending" }
        database.execute(
            "INSERT INTO protected_payload_roots(root,deleting) VALUES(?,0) " +
                "ON CONFLICT(root) DO UPDATE SET cleanup_pending=1",
            root,
        )
        val generation = ProtectedPayloadGeneration(
            UUID.randomUUID(),
            (bytes + PROTECTED_CHUNK_BYTES - 1) / PROTECTED_CHUNK_BYTES,
        )
        database.execute(
            "INSERT INTO protected_payload_generations(root,generation,chunks) VALUES(?,?,?)",
            root,
            generation.id.toString(),
            generation.chunks,
        )
        generation
    }

    fun generations(root: String): List<ProtectedPayloadGeneration> = database.query(
        "SELECT generation,chunks FROM protected_payload_generations WHERE root=? ORDER BY rowid",
        root,
    ) { ProtectedPayloadGeneration(UUID.fromString(getText(0)), getLong(1).toInt()) }

    fun isDeleting(root: String): Boolean = database.query(
        "SELECT deleting FROM protected_payload_roots WHERE root=?",
        root,
    ) { getLong(0) != 0L }.singleOrNull() == true

    fun contains(root: String): Boolean = database.query(
        "SELECT count(*) FROM protected_payload_roots WHERE root=?",
        root,
    ) { getLong(0) }.single() != 0L

    fun markPointerAttempt(root: String) {
        check(contains(root) && !isDeleting(root))
        database.execute("UPDATE protected_payload_roots SET pointer_attempted=1 WHERE root=?", root)
    }

    fun pointerAttempted(root: String): Boolean = database.query(
        "SELECT pointer_attempted FROM protected_payload_roots WHERE root=?",
        root,
    ) { getLong(0) != 0L }.singleOrNull() == true

    fun pendingRoots(): List<String> = database.query(
        "SELECT root FROM protected_payload_roots WHERE cleanup_pending=1 ORDER BY rowid",
    ) { getText(0) }

    fun markClean(root: String) {
        database.execute("UPDATE protected_payload_roots SET cleanup_pending=0 WHERE root=? AND deleting=0", root)
    }

    /** Only after observing no protected manifest and deleting all uncommitted owned generations. */
    fun abandon(root: String) = database.transaction {
        check(!isDeleting(root) && !pointerAttempted(root) && generations(root).isEmpty())
        database.execute("DELETE FROM protected_payload_roots WHERE root=?", root)
    }

    /** Commit before deleting the protected pointer so a crash cannot resurrect the legacy value. */
    fun markDeleting(root: String) {
        protectedManifestKey(root)
        database.execute(
            "INSERT INTO protected_payload_roots(root,deleting) VALUES(?,1) " +
                "ON CONFLICT(root) DO UPDATE SET deleting=1,cleanup_pending=1",
            root,
        )
    }

    /** Caller must first confirm every indexed chunk in this exact generation was deleted. */
    fun forget(root: String, generation: ProtectedPayloadGeneration) = database.transaction {
        check(generations(root).single { it.id == generation.id } == generation)
        database.execute(
            "DELETE FROM protected_payload_generations WHERE root=? AND generation=?",
            root,
            generation.id.toString(),
        )
    }

    /** Caller must first confirm protected manifest and legacy root deletion. */
    fun finishDelete(root: String) = database.transaction {
        check(isDeleting(root) && generations(root).isEmpty())
        database.execute("DELETE FROM protected_payload_roots WHERE root=?", root)
    }
}
