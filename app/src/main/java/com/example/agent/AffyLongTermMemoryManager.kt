package com.example.agent

import android.content.Context
import android.util.Log
import com.example.data.db.AffyDatabase
import com.example.data.db.AgentMemoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Long-Term Memory Core for AFFY.
 * Persists user preferences, project context, ongoing tasks, important decisions,
 * and conversational context across Gemini Live session restarts.
 *
 * Strictly enforces security: Never saves or logs PINs, passwords, OTPs, or auth secrets.
 */
class AffyLongTermMemoryManager private constructor(
    private val context: Context,
    private val database: AffyDatabase = AffyDatabase.getDatabase(context)
) {
    companion object {
        private const val TAG = "AffyMemoryCore"

        @Volatile
        private var INSTANCE: AffyLongTermMemoryManager? = null

        fun getInstance(context: Context): AffyLongTermMemoryManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AffyLongTermMemoryManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // Sensitive pattern detector
        private val SENSITIVE_PATTERNS = listOf(
            Regex("(?i)\\b(pin|password|passcode|secret|otp|cvv|token|private_key|api_key)\\s*[:=]?\\s*\\w+"),
            Regex("(?i)\\b\\d{4,8}\\b\\s*(?:hai|is)?\\s*(?:pin|password|otp|code)"),
            Regex("(?i)(?:pin|password|otp|code)\\s*(?:hai|is)?\\s*\\b\\d{4,8}\\b"),
            Regex("(?i)\\b(?:bearer\\s+[a-zA-Z0-9_\\-\\.]+)\\b"),
            Regex("(?i)\\b(?:AIza[0-9A-Za-z\\-_]{35})\\b") // Google API Key signature
        )
    }

    // In-memory cache of non-sensitive persistent memories for fast injection into Live sessions
    private val memoryCache = ConcurrentHashMap<String, AgentMemoryEntity>()

    init {
        // Initial warmup of memory cache on background thread
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = database.conversationDao().getAllMemoriesList()
                for (m in list) {
                    if (!containsSensitiveData(m.value) && !containsSensitiveData(m.key)) {
                        memoryCache[m.key] = m
                    }
                }
                Log.i(TAG, "Memory cache warmed up with ${memoryCache.size} persistent memories")
            } catch (e: Exception) {
                Log.w(TAG, "Error warming up memory cache: ${e.message}")
            }
        }
    }

    /**
     * Checks if text contains any sensitive authentication secrets, PINs, or credentials.
     */
    fun containsSensitiveData(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.ROOT)
        
        // Explicit word checks
        if (lower.contains("password") || lower.contains("passcode") || lower.contains("otp") ||
            lower.contains("cvv") || lower.contains("credit card") || lower.contains("secret key")) {
            return true
        }

        return SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Explicit memory storage command handling:
     * e.g. "Affy, ye yaad rakhna ke mujhe classical music pasand hai"
     */
    suspend fun rememberFact(rawInput: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = rawInput.trim()
        if (clean.isBlank()) {
            return@withContext Pair(false, "Yaad rakhne ke liye koi baat nahi batayi aapne.")
        }

        // Security check: PIN, password, OTP, auth credentials, tokens, banking credentials
        if (containsSensitiveData(clean)) {
            Log.w(TAG, "[SECURITY_GUARD] Rejected attempt to store sensitive secret in memory")
            AffyDiagnostics.logAction("MEMORY", "SECURITY_REJECTION", "Sensitive data blocked from long-term memory", false)
            return@withContext Pair(
                false,
                "Security policy ke mutabiq passwords, PINs ya OTPs save nahi kiye ja sakte."
            )
        }

        // Strip trigger phrases
        var factText = clean
            .replace(Regex("(?i)^(?:affy|hey affy|suno affy|please)\\s*"), "")
            .replace(Regex("(?i)^(?:ye|yeh|is baat ko)?\\s*(?:yaad rakhna|yaad rakho|yaad rakh|remember this|remember that|remember to|remember my name is|remember my name|remember|yaad rahe|memory mein save karo|memory me save karo|save in memory|save to memory|save this|note down|note kar lo)\\s*(?:ke|ki|that|:|to|is)?\\s*"), "")
            .replace(Regex("(?i)\\s*(?:ye|yeh)?\\s*(?:yaad rakhna|yaad rakho|yaad rakh|remember this|remember that|remember my name|remember it|remember|memory mein save karo|memory me save karo|save in memory|save this)\\s*[.!?]?$"), "")
            .trim(' ', ':', '-', '"', '\'', '.')

        if (factText.isBlank()) {
            factText = clean
        }

        // Categorize memory
        val category = when {
            factText.contains("my name is", ignoreCase = true) || factText.contains("mera naam", ignoreCase = true) ||
            factText.contains("naam hai", ignoreCase = true) || factText.contains("i am called", ignoreCase = true) -> "user_identity"

            factText.contains("pasand", ignoreCase = true) || factText.contains("favorite", ignoreCase = true) ||
            factText.contains("like", ignoreCase = true) || factText.contains("prefer", ignoreCase = true) ||
            factText.contains("roman urdu", ignoreCase = true) || factText.contains("english", ignoreCase = true) ||
            factText.contains("urdu", ignoreCase = true) || factText.contains("hindi", ignoreCase = true) -> "user_preference"

            factText.contains("jab main", ignoreCase = true) || factText.contains("instruction", ignoreCase = true) ||
            factText.contains("short jawab", ignoreCase = true) || factText.contains("detail jawab", ignoreCase = true) ||
            factText.contains("boss bolun", ignoreCase = true) || factText.contains("hamesha", ignoreCase = true) -> "user_instruction"

            factText.contains("project", ignoreCase = true) || factText.contains("code", ignoreCase = true) ||
            factText.contains("app", ignoreCase = true) || factText.contains("focus", ignoreCase = true) ||
            factText.contains("jarvis", ignoreCase = true) || factText.contains("deadline", ignoreCase = true) -> "project_context"

            factText.contains("meeting", ignoreCase = true) || factText.contains("task", ignoreCase = true) ||
            factText.contains("kaam", ignoreCase = true) || factText.contains("bhejna hai", ignoreCase = true) -> "ongoing_task"

            factText.contains("decided", ignoreCase = true) || factText.contains("faisla", ignoreCase = true) ||
            factText.contains("final", ignoreCase = true) -> "important_decision"

            else -> "important_fact"
        }

        // Check for existing conflicting or overlapping memory in this category to update instead of duplicating
        val existingMemory = findConflictingMemory(factText, category)
        val key = existingMemory?.key ?: generateMemoryKey(factText, category)
        val isUpdate = existingMemory != null

        val memoryEntity = AgentMemoryEntity(
            key = key,
            value = factText,
            category = category,
            updatedAt = System.currentTimeMillis()
        )

        try {
            database.conversationDao().putMemory(memoryEntity)
            memoryCache[key] = memoryEntity
            val actionTag = if (isUpdate) "MEMORY_UPDATED" else "MEMORY_CREATED"
            Log.i(TAG, "[$actionTag] key='$key' category='$category' text='$factText'")
            AffyDiagnostics.logAction("MEMORY", actionTag, "Key: $key ($category)", true)

            val displayCategory = when (category) {
                "user_identity" -> "Aapka naam / identity"
                "user_preference" -> "Aapki preference"
                "user_instruction" -> "Aapki instruction"
                "project_context" -> "Project context"
                "ongoing_task" -> "Yeh task"
                "important_decision" -> "Yeh decision"
                else -> "Yeh baat"
            }

            val msg = if (isUpdate) {
                "Ji boss! $displayCategory update kar di hai: '$factText'"
            } else {
                "Ji boss! $displayCategory maine yaad rakh li hai: '$factText'"
            }
            Pair(true, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memory: ${e.message}", e)
            Pair(false, "Memory save karne mein problem hui.")
        }
    }

    private fun findConflictingMemory(newFact: String, category: String): AgentMemoryEntity? {
        val lower = newFact.lowercase(Locale.ROOT)
        return memoryCache.values.firstOrNull { existing ->
            val exLower = existing.value.lowercase(Locale.ROOT)
            when {
                // Name / Identity overlap (e.g. "my name is Aftab" vs "mera naam Aftab hai")
                (category == "user_identity" || lower.contains("name is") || lower.contains("naam hai") || lower.contains("mera naam")) &&
                        (existing.category == "user_identity" || exLower.contains("name is") || exLower.contains("naam hai") || exLower.contains("mera naam")) -> true

                // Language preference overlap (e.g. "roman urdu" vs "english")
                category == "user_preference" && (lower.contains("roman urdu") || lower.contains("english") || lower.contains("urdu")) &&
                        (exLower.contains("roman urdu") || exLower.contains("english") || exLower.contains("urdu") || exLower.contains("jawab dena")) -> true

                // Response length / style overlap (e.g. "short jawab" vs "detail jawab")
                (lower.contains("short jawab") || lower.contains("mukhtasar")) && (exLower.contains("short jawab") || exLower.contains("detail")) -> true

                // Boss instruction overlap
                lower.contains("boss bolun") && exLower.contains("boss bolun") -> true

                // Project focus overlap
                category == "project_context" && lower.contains("focus") && exLower.contains("focus") -> true

                // Direct key match
                existing.category == category && existing.key == generateMemoryKey(newFact, category) -> true

                else -> false
            }
        }
    }

    /**
     * Explicit memory deletion:
     * e.g. "Affy, ye bhool jao", "meeting wali baat bhool jao", "is memory ko delete karo"
     */
    suspend fun forgetFact(query: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val clean = query
            .replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), "")
            .replace(Regex("(?i)^(?:ye|yeh|is baat ko|jo tumne mere bare mein save kiya hai wo)?\\s*(?:bhool jao|forget this|delete memory|is memory ko delete karo|remove karo|delete karo)\\s*(?:ke|ki|that|:)?\\s*"), "")
            .replace(Regex("(?i)\\s*(?:bhool jao|forget this|delete karo)\\s*$"), "")
            .trim()

        if (clean.isBlank() || clean.equals("sab", ignoreCase = true) || clean.contains("all") || clean.contains("saari") || clean.contains("jo tumne mere bare mein save")) {
            return@withContext clearAll()
        }

        val allMemories = database.conversationDao().getAllMemoriesList()
        val toDelete = allMemories.filter { 
            it.value.contains(clean, ignoreCase = true) || it.key.contains(clean, ignoreCase = true) 
        }

        if (toDelete.isEmpty()) {
            return@withContext Pair(false, "Memory mein '$clean' se related koi record nahi mila.")
        }

        for (item in toDelete) {
            database.conversationDao().deleteMemory(item.key)
            memoryCache.remove(item.key)
            Log.i(TAG, "[MEMORY_DELETED] ${item.key}")
        }

        AffyDiagnostics.logAction("MEMORY", "MEMORY_DELETED", "Deleted ${toDelete.size} items matching '$clean'", true)
        Pair(true, "Theek hai boss! Maine memory se '$clean' remove kar diya hai.")
    }

    /**
     * Recalls all or keyword-matched persistent memories.
     * e.g. "Affy, pichli baat yaad hai?", "kya yaad hai?", "mere bare mein kya yaad hai?"
     */
    suspend fun recallMemories(query: String? = null): String = withContext(Dispatchers.IO) {
        val allMemories = database.conversationDao().getAllMemoriesList()
            .filter { !containsSensitiveData(it.value) }

        if (allMemories.isEmpty()) {
            AffyDiagnostics.logAction("MEMORY", "MEMORY_RETRIEVED", "Count: 0", true)
            return@withContext "Boss, abhi memory mein koi saved fact ya preference save nahi hai."
        }

        val relevant = if (!query.isNullOrBlank() && !query.contains("kya yaad") && !query.contains("pichli baat") && !query.contains("mere bare mein")) {
            val cleanQ = query.lowercase().trim()
            val filtered = allMemories.filter { it.value.lowercase().contains(cleanQ) || it.key.contains(cleanQ) }
            if (filtered.isNotEmpty()) filtered else allMemories
        } else {
            allMemories
        }

        AffyDiagnostics.logAction("MEMORY", "MEMORY_RETRIEVED", "Count: ${relevant.size}", true)
        val listStr = relevant.take(8).joinToString("\n") { "• ${it.value}" }
        "Ji boss! Mujhe aapke bare mein ye baatein yaad hain:\n$listStr"
    }

    /**
     * Clears all non-essential memories.
     */
    suspend fun clearAll(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            database.conversationDao().clearAllMemories()
            memoryCache.clear()
            AffyDiagnostics.logAction("MEMORY", "MEMORY_DELETED", "All memories cleared", true)
            Pair(true, "Maine memory se saari purani baatein clear kar di hain.")
        } catch (e: Exception) {
            Pair(false, "Memory clear karne mein error aya.")
        }
    }

    /**
     * Returns a concise, sanitized context snippet of user's persistent long-term memories
     * and recent context to inject into Gemini Live session setup / prompt.
     */
    fun getLiveSessionMemoryContext(): String {
        val memories = if (memoryCache.isNotEmpty()) {
            memoryCache.values.toList()
        } else {
            try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    val list = database.conversationDao().getAllMemoriesList()
                    for (m in list) {
                        if (!containsSensitiveData(m.value) && !containsSensitiveData(m.key)) {
                            memoryCache[m.key] = m
                        }
                    }
                    list
                }
            } catch (e: Exception) {
                emptyList()
            }
        }.filter { !containsSensitiveData(it.value) }

        val recentConvSummary = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val recent = database.conversationDao().getRecentConversationsList(4)
                if (recent.isNotEmpty()) {
                    recent.reversed().joinToString("\n") { "${it.sender}: ${it.message}" }
                } else ""
            }
        } catch (e: Exception) {
            ""
        }

        val sb = StringBuilder()
        if (memories.isNotEmpty()) {
            sb.append("\n\n[PERSISTENT USER MEMORIES (LONG-TERM MEMORY)]\n")
            sb.append("CRITICAL JARVIS MEMORY RULE: You are the EXACT SAME assistant across disconnects and reconnects. The user has explicitly asked you to remember the following persistent facts, identity details, and preferences. You MUST know and utilize them with 100% continuity:\n")
            for (m in memories.take(20)) {
                sb.append("- [${m.category}]: ${m.value}\n")
            }
            sb.append("When asked who the user is, what the user's name is, what you know/remember about the user or their friends/preferences, answer immediately and accurately based on these persistent memories without asking the user to teach you again.\n")
        }

        if (recentConvSummary.isNotBlank()) {
            sb.append("\n[RECENT CONVERSATIONAL CONTEXT BEFORE RECONNECT]\n")
            sb.append(recentConvSummary)
            sb.append("\nMaintain natural conversational continuity from before disconnect where appropriate.\n")
        }

        val activeContext = AffyConversationContextManager.getInstance().getSnapshot()
        if (!activeContext.currentIntent.isNullOrBlank() || !activeContext.recentAffyResponse.isNullOrBlank()) {
            sb.append("\n[ACTIVE CONVERSATION CHECKPOINT]\n")
            activeContext.currentIntent?.let { sb.append("Intent: $it\n") }
            activeContext.previousUtterance?.let { sb.append("Last User Utterance: '$it'\n") }
            activeContext.recentAffyResponse?.let { sb.append("Last AFFY Response: '$it'\n") }
        }

        return sb.toString()
    }

    private fun generateMemoryKey(text: String, category: String): String {
        val clean = text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(" ")
            .filter { it.length > 2 }
            .take(4)
            .joinToString("_")

        val prefix = when (category) {
            "user_identity" -> "user"
            "user_preference" -> "pref"
            "user_instruction" -> "inst"
            "project_context" -> "proj"
            "ongoing_task" -> "task"
            "important_decision" -> "decision"
            else -> "fact"
        }

        return if (clean.isNotBlank()) "${prefix}_$clean" else "${prefix}_${System.currentTimeMillis() % 10000}"
    }
}
