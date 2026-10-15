package com.example.agent

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.datastore.AffyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class EmailSummary(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val subject: String,
    val snippet: String,
    val timestamp: Long = System.currentTimeMillis(),
    val account: String = ""
)

enum class GmailAuthStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHORIZATION_REQUIRED
}

class AffyGmailManager(
    private val context: Context,
    private val preferences: AffyPreferences = AffyPreferences(context)
) {
    companion object {
        private const val TAG = "AffyGmail"
        const val GMAIL_PACKAGE = "com.google.android.gm"
        const val SCOPE_GMAIL_READONLY = "oauth2:https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_GMAIL_SEND = "oauth2:https://www.googleapis.com/auth/gmail.send"
    }

    private val _authStatus = MutableStateFlow(GmailAuthStatus.DISCONNECTED)
    val authStatus: StateFlow<GmailAuthStatus> = _authStatus.asStateFlow()

    private val _connectedAccount = MutableStateFlow<String>("")
    val connectedAccount: StateFlow<String> = _connectedAccount.asStateFlow()

    private val _latestEmails = MutableStateFlow<List<EmailSummary>>(emptyList())
    val latestEmails: StateFlow<List<EmailSummary>> = _latestEmails.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _draftReply = MutableStateFlow<Pair<EmailSummary, String>?>(null)
    val draftReply: StateFlow<Pair<EmailSummary, String>?> = _draftReply.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        // Check if previously connected account was stored
        try {
            scope.launch {
                val saved = preferences.gmailConnectedAccountFlow.first()
                if (saved.isNotBlank()) {
                    _connectedAccount.value = saved
                    _authStatus.value = GmailAuthStatus.CONNECTED
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading saved Gmail account: ${e.message}")
        }
    }

    /**
     * Updates inbox intelligence in real-time from incoming Gmail status bar notifications.
     */
    fun onGmailNotificationReceived(
        title: String,
        text: String,
        subText: String? = null
    ) {
        val cleanTitle = title.trim()
        val cleanText = text.trim()

        if (cleanTitle.isBlank() && cleanText.isBlank()) return

        Log.d(TAG, "Gmail notification intercepted: title='$cleanTitle', text='$cleanText', subText='$subText'")

        // Estimate unread count if title contains numbers e.g. "3 new messages"
        if (cleanTitle.contains("new message", ignoreCase = true) || cleanText.contains("new message", ignoreCase = true)) {
            val digits = "\\d+".toRegex().find(cleanTitle)?.value?.toIntOrNull()
                ?: "\\d+".toRegex().find(cleanText)?.value?.toIntOrNull() ?: 1
            _unreadCount.value = maxOf(_unreadCount.value, digits)
        } else {
            _unreadCount.value = _unreadCount.value + 1
        }

        val email = EmailSummary(
            sender = if (cleanTitle.contains("new message", ignoreCase = true)) "Gmail" else cleanTitle,
            subject = if (cleanText.contains(":")) cleanText.substringBefore(":") else cleanText.take(40),
            snippet = cleanText,
            account = subText ?: _connectedAccount.value
        )

        _latestEmails.value = (listOf(email) + _latestEmails.value).take(10)
    }

    /**
     * Natural command: "Affy check karo koi mail aya hai"
     */
    fun checkNewMails(): String {
        val count = _unreadCount.value
        val latest = _latestEmails.value.firstOrNull()

        return when {
            latest != null -> {
                "Boss, ${latest.sender} se email aya hai. Subject hai: '${latest.subject}'."
            }
            count > 0 -> {
                "Boss, aapke paas $count unread emails hain."
            }
            else -> {
                "Boss, abhi koi naya unread email nahi hai."
            }
        }
    }

    /**
     * Natural command: "Unread emails kitni hain?"
     */
    fun getUnreadCountMessage(): String {
        val count = _unreadCount.value
        return if (count == 0) {
            "Boss, abhi koi unread email nahi hai, inbox bilkul clean hai."
        } else {
            "Boss, total $count unread emails hain."
        }
    }

    /**
     * Natural command: "Sabse latest email parho"
     */
    fun readLatestEmail(): String {
        val latest = _latestEmails.value.firstOrNull()
        return if (latest != null) {
            "Latest email ${latest.sender} ki taraf se hai. Subject hai: '${latest.subject}'. Email mein likha hai: '${latest.snippet}'."
        } else {
            "Boss, abhi koi email record mein nahi mila."
        }
    }

    /**
     * Drafts a reply to the latest email
     */
    fun stageReply(replyText: String): Pair<EmailSummary, String>? {
        val latest = _latestEmails.value.firstOrNull() ?: return null
        val draft = Pair(latest, replyText)
        _draftReply.value = draft
        return draft
    }

    fun clearDraft() {
        _draftReply.value = null
    }

    /**
     * Creates Google Account Intent for account selection
     */
    fun createAccountPickerIntent(): Intent {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
        }
    }

    suspend fun onAccountSelected(accountName: String) {
        _connectedAccount.value = accountName
        _authStatus.value = GmailAuthStatus.CONNECTED
        preferences.saveGmailConnectedAccount(accountName)
        Log.i(TAG, "Gmail account linked securely: $accountName")
    }

    suspend fun disconnect() {
        _connectedAccount.value = ""
        _authStatus.value = GmailAuthStatus.DISCONNECTED
        preferences.saveGmailConnectedAccount("")
    }
}
