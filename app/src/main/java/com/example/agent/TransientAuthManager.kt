package com.example.agent

/**
 * Manages strictly transient, in-memory authentication input.
 *
 * Rules:
 * - Treated ONLY as volatile in-memory state with an automatic 30-second expiry.
 * - NEVER persisted to disk, Room database, DataStore, SharedPreferences, or files.
 * - NEVER sent to diagnostics, logs, or external models.
 * - Cleared immediately after the authorized operation finishes.
 */
object TransientAuthManager {
    @Volatile
    private var transientAuthToken: String? = null

    @Volatile
    private var expiryTimestamp: Long = 0L

    /**
     * Stores a transient authentication value with a short TTL (default 30 seconds).
     */
    fun setTransientAuth(value: String, ttlMs: Long = 30_000L) {
        val clean = value.trim()
        if (clean.isNotBlank()) {
            transientAuthToken = clean
            expiryTimestamp = System.currentTimeMillis() + ttlMs
        }
    }

    /**
     * Retrieves the transient value if unexpired, or null.
     */
    fun getTransientAuth(): String? {
        val now = System.currentTimeMillis()
        return if (transientAuthToken != null && now < expiryTimestamp) {
            transientAuthToken
        } else {
            clear()
            null
        }
    }

    /**
     * Immediately clears the transient auth value.
     */
    fun clear() {
        transientAuthToken = null
        expiryTimestamp = 0L
    }
}
