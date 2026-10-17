package com.example.agent

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

sealed class ContactResolutionResult {
    data class Single(val name: String, val phoneNumber: String) : ContactResolutionResult()
    data class Multiple(val candidates: List<ContactCandidate>) : ContactResolutionResult()
    object NotFound : ContactResolutionResult()
    object PermissionMissing : ContactResolutionResult()
}

data class ContactCandidate(
    val name: String,
    val phoneNumber: String,
    val typeLabel: String = "Mobile"
) {
    val numberSuffix: String
        get() {
            val digits = phoneNumber.replace(Regex("[^0-9]"), "")
            return if (digits.length >= 4) "..." + digits.takeLast(4) else phoneNumber
        }

    val displayString: String
        get() {
            val type = if (typeLabel.isNotBlank()) typeLabel else "Mobile"
            return "$name — $type — $numberSuffix"
        }
}

class ContactResolver(private val context: Context) {

    companion object {
        private const val TAG = "ContactResolver"

        /**
         * Normalizes names by:
         * - Lowercasing
         * - Removing common honorifics/connectors if needed or punctuation
         * - Stripping extra whitespace and punctuation
         */
        fun normalizeName(name: String): String {
            return name.lowercase()
                .replace(Regex("[^a-z0-9\\u0600-\\u06FF\\s]"), " ") // Preserve Urdu/Arabic unicode
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Cleans queries by stripping out phone, call, message, and WhatsApp conversational phrases and noise words
         */
        fun cleanContactQuery(query: String): String {
            var text = query.trim()
            text = text.replace(
                Regex("(?i)\\b(affy|please|call karo|call lagao|call milao|call kar do|call laga do|call mila do|phone karo|phone lagao|phone kar do|ko call|ko phone|call|phone|dial|karo|kar do|lagao|laga do|milao|mila do|karna|lagana|ko|pe|par|whatsapp karo|whatsapp kar do|whatsapp bhejo|whatsapp bhej do|whatsapp pe|whatsapp par|whatsapp|msg karo|msg kar do|msg bhejo|msg bhej do|message karo|message kar do|message bhejo|message bhej do|msg|message|text karo|text kar do|text|bhejo|bhej do|send karo|send kar do|send|likho|likh do|bol do)\\b"),
                " "
            )
            return text.replace(Regex("\\s+"), " ").trim()
        }

        /**
         * Normalizes phone numbers to standard canonical form for deduplication:
         * - Removes spaces, dashes, brackets, dots
         * - Normalizes international prefixes (+92, 0092, 92, 03xx)
         */
        fun normalizePhoneNumber(rawNumber: String): String {
            var digitsOnly = rawNumber.replace(Regex("[^0-9+]"), "")
            if (digitsOnly.startsWith("00")) {
                digitsOnly = "+" + digitsOnly.substring(2)
            } else if (digitsOnly.startsWith("92") && digitsOnly.length == 12) {
                digitsOnly = "+92" + digitsOnly.substring(2)
            } else if (digitsOnly.startsWith("03") && digitsOnly.length == 11) {
                // Local Pakistan mobile format
                digitsOnly = "+92" + digitsOnly.substring(1)
            }
            return digitsOnly
        }

        /**
         * Extract core significant digits (last 9-10 digits) to detect matching numbers across formats:
         * e.g., "03001234567", "+923001234567", "00923001234567", "923001234567" -> "3001234567"
         */
        fun getCoreNumber(normalizedNumber: String): String {
            val digits = normalizedNumber.replace(Regex("[^0-9]"), "")
            return if (digits.length >= 10) digits.takeLast(10) else if (digits.length >= 9) digits.takeLast(9) else digits
        }

        /**
         * Validates that a phone number string represents a real, non-invented dialable/WhatsApp phone number.
         */
        fun isValidPhoneNumber(rawNumber: String?): Boolean {
            if (rawNumber.isNullOrBlank()) return false
            val trimmed = rawNumber.trim()
            if (!trimmed.matches(Regex("^\\+?[0-9\\s\\-()]{7,20}$"))) return false
            val digits = trimmed.replace(Regex("[^0-9]"), "")
            if (digits.length !in 7..15) return false
            if (digits.all { it == digits[0] }) return false
            if (digits == "1234567" || digits == "12345678" || digits == "123456789" || digits == "1234567890") return false
            return true
        }

        /**
         * Strictly checks whether a candidate/detected chat name or phone matches an expected target identity.
         * Never matches distinct names or arbitrary mid-word substrings.
         */
        fun doesIdentityMatch(
            candidateIdentity: String?,
            expectedIdentity: String?,
            expectedPhone: String? = null
        ): Boolean {
            if (candidateIdentity.isNullOrBlank()) return false

            val cleanCandidate = candidateIdentity
                .replace(Regex("(?i)^unknown number\\s*\\((.*)\\)$"), "$1")
                .replace(Regex("\\(\\d+\\s+messages?\\)", RegexOption.IGNORE_CASE), "")
                .trim()

            // 1. Check phone number identity match if both have valid digits
            val candDigits = cleanCandidate.replace(Regex("[^0-9]"), "")
            val expPhoneDigits = (expectedPhone ?: expectedIdentity ?: "").replace(Regex("[^0-9]"), "")
            if (candDigits.length >= 7 && expPhoneDigits.length >= 7) {
                val candCore = getCoreNumber(candDigits)
                val expCore = getCoreNumber(expPhoneDigits)
                if (candCore.isNotBlank() && expCore.isNotBlank() &&
                    (candCore == expCore || candCore.endsWith(expCore.takeLast(7)) || expCore.endsWith(candCore.takeLast(7)))
                ) {
                    return true
                }
            }

            if (expectedIdentity.isNullOrBlank()) return false
            val cleanExpected = expectedIdentity
                .replace(Regex("(?i)^unknown number\\s*\\((.*)\\)$"), "$1")
                .replace(Regex("\\(\\d+\\s+messages?\\)", RegexOption.IGNORE_CASE), "")
                .trim()

            val normCand = normalizeName(cleanCandidate)
            val normExp = normalizeName(cleanExpected)
            if (normCand.isBlank() || normExp.isBlank()) return false

            if (normCand == normExp) return true

            val candTokens = normCand.split(" ").filter { it.isNotBlank() }
            val expTokens = normExp.split(" ").filter { it.isNotBlank() }
            if (candTokens.isEmpty() || expTokens.isEmpty()) return false

            // All tokens of expected identity match tokens of candidate (e.g. "Hamza" matches "Hamza" or "Hamza Khan", or vice versa when first token matches)
            if (expTokens.all { et -> candTokens.contains(et) }) return true
            if (candTokens.all { ct -> expTokens.contains(ct) }) return true

            return false
        }
    }

    fun resolveContact(query: String): ContactResolutionResult {
        val rawClean = query.trim()
        if (rawClean.isBlank()) {
            AffyDiagnostics.logContactStage("CONTACT_QUERY", details = "Query is blank", isSuccess = false)
            return ContactResolutionResult.NotFound
        }

        // Screen-first protection: Do NOT search contacts if user is referring to visible on-screen items
        val lower = rawClean.lowercase()
        val isScreenTargetQuery = lower.contains("ye chat") || lower.contains("this chat") ||
                lower.contains("is chat") || lower.contains("jo chat open") || lower.contains("current chat") ||
                lower.contains("jo samne chat") || lower.contains("samne wali chat") || lower.contains("ye video") ||
                lower.contains("this video") || lower.contains("this button") || lower.contains("ye button") ||
                lower.contains("jo screen par") || lower.contains("screen par jo") || lower.contains("current_chat")
        if (isScreenTargetQuery) {
            Log.i(TAG, "[SCREEN_FIRST_PRIORITY] Query '$rawClean' is a visual screen reference. Bypassing Contacts search.")
            return ContactResolutionResult.NotFound
        }

        AffyDiagnostics.logContactStage("CONTACT_QUERY", details = "Query: '$rawClean'")

        val isSelfQuery = rawClean.equals("mujhe", ignoreCase = true) ||
                rawClean.equals("me", ignoreCase = true) ||
                rawClean.equals("myself", ignoreCase = true) ||
                rawClean.equals("apne aap ko", ignoreCase = true) ||
                rawClean.equals("apna number", ignoreCase = true)

        // If the query contains a direct phone number (e.g. "03001234567", "+92300...", or "call 03001234567")
        val directDigits = rawClean.replace(Regex("[^0-9+]"), "")
        if (directDigits.length >= 7) {
            val normalizedNum = normalizePhoneNumber(directDigits)
            AffyDiagnostics.logContactStage(
                "CONTACT_SELECTED",
                details = "Direct phone number provided: $normalizedNum",
                isSuccess = true
            )
            return ContactResolutionResult.Single(name = directDigits, phoneNumber = normalizedNum)
        }

        val strippedQuery = cleanContactQuery(rawClean)
        val candidateQuery = if (strippedQuery.isNotBlank()) strippedQuery else rawClean
        val cleanQuery = normalizeName(candidateQuery)
        if (cleanQuery.isBlank() || (cleanQuery.length < 2 && !rawClean.matches(Regex("\\d+")))) {
            AffyDiagnostics.logContactStage("CONTACT_QUERY", details = "Query too short or blank after cleaning: '$cleanQuery'", isSuccess = false)
            return ContactResolutionResult.NotFound
        }

        data class RawContactRecord(
            val contactId: Long,
            val name: String,
            val number: String,
            val typeLabel: String
        )

        val rawCandidates = mutableListOf<RawContactRecord>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL
            )

            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val labelIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getLong(idIndex) else 0L
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else null
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else null
                    val typeInt = if (typeIndex >= 0) it.getInt(typeIndex) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                    val customLabel = if (labelIndex >= 0) it.getString(labelIndex) else null
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        typeInt,
                        customLabel
                    ).toString()

                    if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                        rawCandidates.add(RawContactRecord(id, name.trim(), number.trim(), typeLabel.ifBlank { "Mobile" }))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission missing: ${e.message}")
            AffyDiagnostics.logContactStage(
                "CONTACT_CANDIDATES",
                details = "READ_CONTACTS permission missing",
                isSuccess = false,
                failureReason = "PERMISSION_MISSING"
            )
            return ContactResolutionResult.PermissionMissing
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}")
            AffyDiagnostics.logContactStage(
                "CONTACT_CANDIDATES",
                details = "Error: ${e.message}",
                isSuccess = false,
                failureReason = e.message
            )
            return ContactResolutionResult.NotFound
        }

        AffyDiagnostics.logContactStage(
            "CONTACT_CANDIDATES",
            details = "Found ${rawCandidates.size} raw phone contact entries"
        )

        if (rawCandidates.isEmpty()) {
            return ContactResolutionResult.NotFound
        }

        // Deduplicate phone numbers for the same person across sync accounts (e.g. Google, WhatsApp, SIM)
        val deduplicatedRecords = mutableListOf<RawContactRecord>()
        val seenUniqueKeys = mutableSetOf<String>()
        for (rec in rawCandidates) {
            val normNum = normalizePhoneNumber(rec.number)
            val coreNum = getCoreNumber(normNum)
            val personKey = if (rec.contactId > 0L) "id_${rec.contactId}_$coreNum" else "${normalizeName(rec.name)}_$coreNum"
            if (seenUniqueKeys.add(personKey)) {
                deduplicatedRecords.add(rec.copy(number = normNum))
            }
        }

        // MATCH PRIORITY:
        // 1. Exact normalized full name match ("Ali" == "Ali", "Hamza Khan" == "Hamza Khan")
        // 2. Exact word token match ("Ali" in "Ali Raza")
        // 3. All query words match tokens ("Ali Raza" matches "Ali Raza Khan")
        // 4. Prefix match (query length >= 3 matches start of token, e.g. "Hamz" matches "Hamza")

        val exactFullMatches = mutableListOf<RawContactRecord>()
        val exactWordMatches = mutableListOf<RawContactRecord>()
        val allWordsMatches = mutableListOf<RawContactRecord>()
        val prefixMatches = mutableListOf<RawContactRecord>()

        if (isSelfQuery) {
            val selfKeywords = listOf("me", "myself", "my number", "you", "apna", "personal", "owner", "mera")
            for (candidate in deduplicatedRecords) {
                val candNormName = normalizeName(candidate.name)
                if (selfKeywords.any { candNormName.contains(it) }) {
                    exactFullMatches.add(candidate)
                }
            }
        }

        val queryWords = cleanQuery.split(" ").filter { it.isNotBlank() }

        for (candidate in deduplicatedRecords) {
            val candNormName = normalizeName(candidate.name)
            val candTokens = candNormName.split(" ").filter { it.isNotBlank() }

            if (candNormName == cleanQuery) {
                exactFullMatches.add(candidate)
            } else if (candTokens.contains(cleanQuery)) {
                exactWordMatches.add(candidate)
            } else if (queryWords.size > 1 && queryWords.all { qw -> candTokens.any { ct -> ct == qw || (qw.length >= 3 && ct.startsWith(qw)) } }) {
                allWordsMatches.add(candidate)
            } else if (cleanQuery.length >= 3 && candTokens.any { it.startsWith(cleanQuery) }) {
                prefixMatches.add(candidate)
            }
        }

        val poolToUse = when {
            exactFullMatches.isNotEmpty() -> exactFullMatches
            exactWordMatches.isNotEmpty() -> exactWordMatches
            allWordsMatches.isNotEmpty() -> allWordsMatches
            prefixMatches.isNotEmpty() -> prefixMatches
            else -> emptyList()
        }

        Log.i(TAG, "[WA_CONTACT_MATCHES] query='$cleanQuery' exactFull=${exactFullMatches.size} exactWord=${exactWordMatches.size} allWords=${allWordsMatches.size} prefix=${prefixMatches.size} selectedPool=${poolToUse.size}")

        if (poolToUse.isEmpty()) {
            AffyDiagnostics.logContactStage(
                "CONTACT_UNIQUE_TARGETS",
                details = "No match found for query '$cleanQuery'",
                isSuccess = false
            )
            return ContactResolutionResult.NotFound
        }

        // Group matching records by individual human person (by contactId or normalized name)
        val personGroups = poolToUse.groupBy { rec ->
            if (rec.contactId > 0L) "id_${rec.contactId}" else normalizeName(rec.name)
        }

        Log.i(TAG, "[WA_CONTACT_PERSON_COUNT] query='$cleanQuery' uniquePersonCount=${personGroups.size}")

        return when {
            personGroups.isEmpty() -> ContactResolutionResult.NotFound

            // Exactly ONE distinct person matched
            personGroups.size == 1 -> {
                val personRecords = personGroups.values.first()
                val distinctNumbers = mutableListOf<RawContactRecord>()
                val seenCores = mutableSetOf<String>()
                for (rec in personRecords) {
                    val core = getCoreNumber(rec.number)
                    if (seenCores.add(core)) {
                        distinctNumbers.add(rec)
                    }
                }

                if (distinctNumbers.size == 1) {
                    val selected = distinctNumbers[0]
                    AffyDiagnostics.logContactStage(
                        "CONTACT_SELECTED",
                        details = "Resolved unique single contact: ${selected.name} (${selected.number})",
                        isSuccess = true
                    )
                    ContactResolutionResult.Single(selected.name, selected.number)
                } else {
                    // Single person has multiple distinct phone numbers (e.g. Mobile and Work)
                    val candidates = distinctNumbers.map {
                        ContactCandidate(it.name, it.number, it.typeLabel)
                    }
                    AffyDiagnostics.logContactStage(
                        "CONTACT_SELECTED",
                        details = "Single contact ${candidates[0].name} has ${candidates.size} phone numbers: ${candidates.map { it.displayString }}",
                        isSuccess = false,
                        failureReason = "MULTIPLE_NUMBERS_SAME_CONTACT"
                    )
                    ContactResolutionResult.Multiple(candidates)
                }
            }

            // Multiple distinct people matched (e.g. "Ali Khan" and "Ali Raza")
            else -> {
                val candidates = mutableListOf<ContactCandidate>()
                for ((_, records) in personGroups) {
                    // Pick the primary/mobile number for each distinct person
                    val primaryRecord = records.firstOrNull { it.typeLabel.equals("mobile", ignoreCase = true) }
                        ?: records.first()
                    candidates.add(ContactCandidate(primaryRecord.name, primaryRecord.number, primaryRecord.typeLabel))
                }

                val sortedCandidates = candidates.sortedWith(
                    compareBy<ContactCandidate> { normalizeName(it.name) != cleanQuery }
                        .thenBy { it.name.lowercase() }
                ).take(5)

                AffyDiagnostics.logContactStage(
                    "CONTACT_SELECTED",
                    details = "Multiple distinct contacts found (${sortedCandidates.size}): ${sortedCandidates.map { it.displayString }}",
                    isSuccess = false,
                    failureReason = "MULTIPLE_CONTACTS"
                )
                ContactResolutionResult.Multiple(sortedCandidates)
            }
        }
    }

    fun initiateCall(phoneNumber: String): Boolean {
        val cleanNumber = phoneNumber.trim()
        AffyDiagnostics.logContactStage(
            "CALL_STARTED",
            details = "Initiating call to $cleanNumber"
        )

        return try {
            val uri = Uri.parse("tel:${Uri.encode(cleanNumber)}")
            val intent = Intent(Intent.ACTION_CALL, uri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            AffyDiagnostics.logContactStage(
                "CALL_VERIFIED",
                details = "ACTION_CALL intent successfully started for $cleanNumber",
                isSuccess = true
            )
            true
        } catch (e: SecurityException) {
            // Fallback to dial intent without requiring dangerous CALL_PHONE permission
            try {
                val uri = Uri.parse("tel:${Uri.encode(cleanNumber)}")
                val intent = Intent(Intent.ACTION_DIAL, uri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                AffyDiagnostics.logContactStage(
                    "CALL_VERIFIED",
                    details = "ACTION_DIAL fallback started for $cleanNumber",
                    isSuccess = true
                )
                true
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to initiate call fallback: ${ex.message}")
                AffyDiagnostics.logContactStage(
                    "CALL_FAILED",
                    details = "Fallback error: ${ex.message}",
                    isSuccess = false,
                    failureReason = ex.message
                )
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call: ${e.message}")
            AffyDiagnostics.logContactStage(
                "CALL_FAILED",
                details = "Error: ${e.message}",
                isSuccess = false,
                failureReason = e.message
            )
            false
        }
    }
}
