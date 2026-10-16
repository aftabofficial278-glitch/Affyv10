package com.example.data.model

data class ContactNumberOption(
    val normalizedNumber: String,
    val displayNumber: String,
    val label: String,
    val isPrimary: Boolean = false
)

data class ContactCandidate(
    val contactId: String,
    val displayName: String,
    val numbers: List<ContactNumberOption>
)

data class PendingContactDisambiguation(
    val requestedName: String,
    val candidates: List<ContactCandidate>,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun isExpired(maxAgeMs: Long = 3 * 60 * 1000L): Boolean {
        return (System.currentTimeMillis() - timestampMs) > maxAgeMs
    }
}
