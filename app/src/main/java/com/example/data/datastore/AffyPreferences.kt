package com.example.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "affy_settings")

class AffyPreferences(private val context: Context) {

    companion object {
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val KEY_MODEL = stringPreferencesKey("gemini_model")
        private val KEY_PERSONALITY = stringPreferencesKey("personality_mode")
        private val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        private val KEY_VOICE_PITCH = floatPreferencesKey("voice_pitch")
        private val KEY_VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        private val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        private val KEY_BACKGROUND_LISTENING = booleanPreferencesKey("background_listening")
        private val KEY_BUSY_MODE_ENABLED = booleanPreferencesKey("busy_mode_enabled")
        private val KEY_AUTO_REPLY_ENABLED = booleanPreferencesKey("auto_reply_enabled")
        private val KEY_BUSY_MODE_DELAY_SECONDS = floatPreferencesKey("busy_mode_delay_seconds")
        private val KEY_BUSY_MODE_GROUP_CHATS = booleanPreferencesKey("busy_mode_group_chats")
        private val KEY_BUSY_MODE_DAILY_LIMIT = floatPreferencesKey("busy_mode_daily_limit")
        private val KEY_AUTO_REPLY_INDIVIDUAL_CHATS = booleanPreferencesKey("auto_reply_individual_chats")
        private val KEY_AUTO_REPLY_UNKNOWN_CONTACTS = booleanPreferencesKey("auto_reply_unknown_contacts")
        private val KEY_AUTO_REPLY_AI_GENERATED = booleanPreferencesKey("auto_reply_ai_generated")
        private val KEY_PREFERRED_WHATSAPP_PACKAGE = stringPreferencesKey("preferred_whatsapp_package")
        private val KEY_PROACTIVE_MODE_ENABLED = booleanPreferencesKey("proactive_mode_enabled")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val KEY_NOTIFICATION_ASSISTANT_ENABLED = booleanPreferencesKey("notification_assistant_enabled")
        private val KEY_QUIET_MODE_ENABLED = booleanPreferencesKey("quiet_mode_enabled")
        private val KEY_GMAIL_CONNECTED_ACCOUNT = stringPreferencesKey("gmail_connected_account")

        // Battery Awareness Preferences
        private val KEY_BATTERY_AWARENESS_ENABLED = booleanPreferencesKey("battery_awareness_enabled")
        private val KEY_CHARGING_DETECTION_ENABLED = booleanPreferencesKey("charging_detection_enabled")
        private val KEY_CHARGER_CONNECTED_VOICE = booleanPreferencesKey("charger_connected_voice")
        private val KEY_CHARGER_DISCONNECTED_VOICE = booleanPreferencesKey("charger_disconnected_voice")
        private val KEY_BATTERY_THRESHOLD_ALERTS = booleanPreferencesKey("battery_threshold_alerts")
        private val KEY_BATTERY_HIGH_THRESHOLD = floatPreferencesKey("battery_high_threshold")
        private val KEY_FULL_BATTERY_ALERT = booleanPreferencesKey("full_battery_alert")
        private val KEY_LOW_BATTERY_ALERT = booleanPreferencesKey("low_battery_alert")
        private val KEY_LOW_BATTERY_THRESHOLD = floatPreferencesKey("low_battery_threshold")

        private val KEY_LIVE_VOICE = stringPreferencesKey("live_voice_name")

        const val DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
        const val DEFAULT_PERSONALITY = "Playful" // Friendly, playful, witty companion
        const val DEFAULT_LIVE_VOICE = "Puck" // Puck (Male) or Leda (Female)
    }

    private fun isValidApiKey(key: String): Boolean {
        val trimmed = key.trim()
        return trimmed.isNotBlank() &&
                !trimmed.equals("your_gemini_api_key_here", ignoreCase = true) &&
                trimmed.length >= 10
    }

    val apiKeyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        val userKey = preferences[KEY_GEMINI_API_KEY]?.trim().orEmpty()
        if (isValidApiKey(userKey)) {
            userKey
        } else {
            val buildKey = BuildConfig.GEMINI_API_KEY.trim()
            if (isValidApiKey(buildKey)) buildKey else ""
        }
    }

    suspend fun getEffectiveApiKey(): String {
        val userKey = context.dataStore.data.first()[KEY_GEMINI_API_KEY]?.trim().orEmpty()
        if (isValidApiKey(userKey)) {
            return userKey
        }
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        return if (isValidApiKey(buildKey)) buildKey else ""
    }

    val userCustomApiKeyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_GEMINI_API_KEY]?.trim().orEmpty()
    }

    val isApiKeyConfiguredFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        val userKey = preferences[KEY_GEMINI_API_KEY]?.trim().orEmpty()
        if (isValidApiKey(userKey)) true
        else isValidApiKey(BuildConfig.GEMINI_API_KEY)
    }

    val maskedApiKeyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        val userKey = preferences[KEY_GEMINI_API_KEY]?.trim().orEmpty()
        val keyToMask = if (isValidApiKey(userKey)) userKey else {
            val buildKey = BuildConfig.GEMINI_API_KEY.trim()
            if (isValidApiKey(buildKey)) buildKey else ""
        }
        maskKey(keyToMask)
    }

    private fun maskKey(key: String): String {
        val clean = key.trim()
        if (clean.isBlank() || clean.equals("your_gemini_api_key_here", ignoreCase = true)) {
            return ""
        }
        return if (clean.length >= 10) {
            "${clean.take(6)}••••••••••••${clean.takeLast(4)}"
        } else {
            "••••••••"
        }
    }

    suspend fun saveApiKey(key: String): Boolean {
        val cleanKey = key.trim()
        if (!isValidApiKey(cleanKey)) {
            return false
        }
        context.dataStore.edit { preferences ->
            preferences[KEY_GEMINI_API_KEY] = cleanKey
        }
        // Verify persistent reload from DataStore
        val verified = context.dataStore.data.first()[KEY_GEMINI_API_KEY]?.trim().orEmpty()
        return verified == cleanKey
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_GEMINI_API_KEY)
        }
    }

    val busyModeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BUSY_MODE_ENABLED] ?: false
    }

    suspend fun saveBusyModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BUSY_MODE_ENABLED] = enabled
        }
    }

    val modelFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_MODEL] ?: DEFAULT_MODEL
    }

    suspend fun saveModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MODEL] = model
        }
    }

    val personalityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_PERSONALITY] ?: DEFAULT_PERSONALITY
    }

    suspend fun savePersonality(personality: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PERSONALITY] = personality
        }
    }

    val voiceSpeedFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOICE_SPEED] ?: 1.05f
    }

    suspend fun saveVoiceSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VOICE_SPEED] = speed
        }
    }

    val voicePitchFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOICE_PITCH] ?: 1.0f
    }

    suspend fun saveVoicePitch(pitch: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VOICE_PITCH] = pitch
        }
    }

    val voiceLanguageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_VOICE_LANGUAGE] ?: "auto"
    }

    suspend fun saveVoiceLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_VOICE_LANGUAGE] = language
        }
    }

    val liveVoiceFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LIVE_VOICE] ?: DEFAULT_LIVE_VOICE
    }

    suspend fun saveLiveVoice(voice: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LIVE_VOICE] = voice
        }
    }

    suspend fun getLiveVoice(): String {
        return context.dataStore.data.first()[KEY_LIVE_VOICE] ?: DEFAULT_LIVE_VOICE
    }

    val wakeWordEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_WAKE_WORD_ENABLED] ?: true
    }

    suspend fun saveWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_WAKE_WORD_ENABLED] = enabled
        }
    }

    val backgroundListeningFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BACKGROUND_LISTENING] ?: false
    }

    suspend fun saveBackgroundListening(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BACKGROUND_LISTENING] = enabled
        }
    }

    val autoReplyEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_REPLY_ENABLED] ?: true
    }

    suspend fun saveAutoReplyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_REPLY_ENABLED] = enabled
        }
    }

    val busyModeDelaySecondsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_BUSY_MODE_DELAY_SECONDS]?.toInt() ?: 3
    }

    suspend fun saveBusyModeDelaySeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BUSY_MODE_DELAY_SECONDS] = seconds.toFloat()
        }
    }

    val busyModeGroupChatsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BUSY_MODE_GROUP_CHATS] ?: false
    }

    suspend fun saveBusyModeGroupChats(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BUSY_MODE_GROUP_CHATS] = enabled
        }
    }

    val busyModeDailyLimitFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_BUSY_MODE_DAILY_LIMIT]?.toInt() ?: 50
    }

    suspend fun saveBusyModeDailyLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BUSY_MODE_DAILY_LIMIT] = limit.toFloat()
        }
    }

    val autoReplyIndividualChatsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_REPLY_INDIVIDUAL_CHATS] ?: true
    }

    suspend fun saveAutoReplyIndividualChats(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_REPLY_INDIVIDUAL_CHATS] = enabled
        }
    }

    val autoReplyUnknownContactsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_REPLY_UNKNOWN_CONTACTS] ?: false
    }

    suspend fun saveAutoReplyUnknownContacts(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_REPLY_UNKNOWN_CONTACTS] = enabled
        }
    }

    val autoReplyAiGeneratedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_REPLY_AI_GENERATED] ?: true
    }

    suspend fun saveAutoReplyAiGenerated(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_REPLY_AI_GENERATED] = enabled
        }
    }

    val preferredWhatsAppPackageFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_PREFERRED_WHATSAPP_PACKAGE] ?: "com.whatsapp"
    }

    suspend fun savePreferredWhatsAppPackage(pkg: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PREFERRED_WHATSAPP_PACKAGE] = pkg
        }
    }

    val proactiveModeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_PROACTIVE_MODE_ENABLED] ?: false
    }

    suspend fun saveProactiveModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PROACTIVE_MODE_ENABLED] = enabled
        }
    }

    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun saveOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    val notificationAssistantEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_NOTIFICATION_ASSISTANT_ENABLED] ?: true
    }

    suspend fun saveNotificationAssistantEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NOTIFICATION_ASSISTANT_ENABLED] = enabled
        }
    }

    val quietModeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_QUIET_MODE_ENABLED] ?: false
    }

    suspend fun saveQuietModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_QUIET_MODE_ENABLED] = enabled
        }
    }

    val gmailConnectedAccountFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_GMAIL_CONNECTED_ACCOUNT] ?: ""
    }

    suspend fun saveGmailConnectedAccount(account: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_GMAIL_CONNECTED_ACCOUNT] = account
        }
    }

    // Battery Awareness Flows & Savers
    val batteryAwarenessEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BATTERY_AWARENESS_ENABLED] ?: true
    }

    suspend fun saveBatteryAwarenessEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BATTERY_AWARENESS_ENABLED] = enabled
        }
    }

    val chargingDetectionEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHARGING_DETECTION_ENABLED] ?: true
    }

    suspend fun saveChargingDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CHARGING_DETECTION_ENABLED] = enabled
        }
    }

    val batteryAnnounceChargingFlow: Flow<Boolean> = chargingDetectionEnabledFlow

    suspend fun saveBatteryAnnounceCharging(enabled: Boolean) {
        saveChargingDetectionEnabled(enabled)
    }

    val chargerConnectedVoiceFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHARGER_CONNECTED_VOICE] ?: true
    }

    suspend fun saveChargerConnectedVoice(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CHARGER_CONNECTED_VOICE] = enabled
        }
    }

    val chargerDisconnectedVoiceFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_CHARGER_DISCONNECTED_VOICE] ?: true
    }

    suspend fun saveChargerDisconnectedVoice(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CHARGER_DISCONNECTED_VOICE] = enabled
        }
    }

    val batteryThresholdAlertsFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_BATTERY_THRESHOLD_ALERTS] ?: true
    }

    suspend fun saveBatteryThresholdAlerts(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BATTERY_THRESHOLD_ALERTS] = enabled
        }
    }

    val batteryHighThresholdFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_BATTERY_HIGH_THRESHOLD] ?: 90f
    }

    suspend fun saveBatteryHighThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BATTERY_HIGH_THRESHOLD] = threshold
        }
    }

    val fullBatteryAlertFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_FULL_BATTERY_ALERT] ?: true
    }

    suspend fun saveFullBatteryAlert(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FULL_BATTERY_ALERT] = enabled
        }
    }

    val batteryAnnounceFullFlow: Flow<Boolean> = fullBatteryAlertFlow

    suspend fun saveBatteryAnnounceFull(enabled: Boolean) {
        saveFullBatteryAlert(enabled)
    }

    val lowBatteryAlertFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_LOW_BATTERY_ALERT] ?: true
    }

    suspend fun saveLowBatteryAlert(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LOW_BATTERY_ALERT] = enabled
        }
    }

    val batteryAnnounceLowFlow: Flow<Boolean> = lowBatteryAlertFlow

    suspend fun saveBatteryAnnounceLow(enabled: Boolean) {
        saveLowBatteryAlert(enabled)
    }

    val lowBatteryThresholdFlow: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[KEY_LOW_BATTERY_THRESHOLD] ?: 20f
    }

    suspend fun saveLowBatteryThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LOW_BATTERY_THRESHOLD] = threshold
        }
    }

    val batteryLowThresholdFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[KEY_LOW_BATTERY_THRESHOLD] ?: 20f).toInt()
    }

    suspend fun saveBatteryLowThreshold(threshold: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LOW_BATTERY_THRESHOLD] = threshold.toFloat()
        }
    }
}
