package net.thunderbird.app.ubuntutouch

internal interface SignonClient : AutoCloseable {
    fun exists(id: Long, key: String): Boolean
    fun reserve(key: String): Long
    fun write(id: Long, key: String, value: ByteArray)
    fun read(id: Long, key: String): ByteArray?
    fun delete(id: Long, key: String)
}

internal class SignonIdentityIndex(private val database: MailDatabase) {
    fun find(key: String): Long? = database.query(
        "SELECT identity_id FROM signon_identity_index WHERE key=?",
        key,
    ) { getLong(0) }.singleOrNull()

    fun put(key: String, id: Long) {
        require(id in 1..MAX_SIGNON_ID)
        database.execute(
            """INSERT INTO signon_identity_index(key,identity_id) VALUES(?,?)
                ON CONFLICT(key) DO UPDATE SET identity_id=excluded.identity_id""",
            key,
            id,
        )
    }

    fun remove(key: String) = database.execute("DELETE FROM signon_identity_index WHERE key=?", key)

    private companion object {
        const val MAX_SIGNON_ID = 4294967295L
    }
}

/** Reserve/index an empty identity before sending any secret. No password is ever stored in the index. */
internal class SignonCredentialStore(
    private val index: SignonIdentityIndex,
    private val createClient: () -> SignonClient = { DbusSignonClient() },
) : CredentialStore {
    @Synchronized
    override fun read(key: String): ByteArray? {
        val id = index.find(key) ?: return null
        return createClient().use { it.read(id, key) }
    }

    @Synchronized
    override fun write(key: String, value: ByteArray) {
        require(key.isNotBlank())
        createClient().use { client ->
            val existing = index.find(key)?.takeIf { client.exists(it, key) }
            val id = existing ?: reserveIndexed(client, key)
            client.write(id, key, value)
        }
    }

    @Synchronized
    override fun delete(key: String) {
        val id = index.find(key) ?: return
        createClient().use { it.delete(id, key) }
        index.remove(key)
    }

    @Suppress("TooGenericExceptionCaught") // Clean up only the empty reservation on any index failure, then rethrow.
    private fun reserveIndexed(client: SignonClient, key: String): Long {
        val id = client.reserve(key)
        try {
            index.put(key, id)
        } catch (failure: Exception) {
            runCatching { client.delete(id, key) }
            throw failure
        }
        return id
    }
}
