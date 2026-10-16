package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.UUID

@JsonClass(generateAdapter = true)
data class AffyPlan(
    @Json(name = "intent") val intent: String,
    @Json(name = "speech_response") val speechResponse: String,
    @Json(name = "actions") val actions: List<AffyActionItem> = emptyList(),
    @Json(name = "requires_confirmation") val requiresConfirmation: Boolean = false,
    @Json(name = "confirmation_prompt") val confirmationPrompt: String? = null,
    @Json(name = "clarification_question") val clarificationQuestion: String? = null
)

@JsonClass(generateAdapter = true)
data class AffyActionItem(
    @Json(name = "type") val type: String,
    @Json(name = "target") val target: String? = null,
    @Json(name = "text") val text: String? = null,
    @Json(name = "value") val value: String? = null,
    @Json(name = "package_name") val packageName: String? = null,
    @Json(name = "extra_data") val extraData: String? = null,
    @Json(name = "request_id") val requestId: String = UUID.randomUUID().toString()
)

object ActionTypes {
    const val OPEN_APP = "OPEN_APP"
    const val TAP = "TAP"
    const val CLICK_ELEMENT = "CLICK_ELEMENT"
    const val TYPE_TEXT = "TYPE_TEXT"
    const val SCROLL = "SCROLL"
    const val SCROLL_DOWN = "SCROLL_DOWN"
    const val SCROLL_UP = "SCROLL_UP"
    const val SWIPE = "SWIPE"
    const val SWIPE_LEFT = "SWIPE_LEFT"
    const val SWIPE_RIGHT = "SWIPE_RIGHT"
    const val GO_BACK = "GO_BACK"
    const val NAVIGATE_BACK = "NAVIGATE_BACK"
    const val GO_HOME = "GO_HOME"
    const val OPEN_RECENTS = "OPEN_RECENTS"
    const val OPEN_NOTIFICATIONS = "OPEN_NOTIFICATIONS"
    const val OPEN_QUICK_SETTINGS = "OPEN_QUICK_SETTINGS"
    const val SEARCH = "SEARCH"
    const val SEARCH_WEB = "SEARCH_WEB"
    const val CALL_CONTACT = "CALL_CONTACT"
    const val SEND_MESSAGE = "SEND_MESSAGE"
    const val SEND_WHATSAPP_MESSAGE = "SEND_WHATSAPP_MESSAGE"
    const val SEND_SMS = "SEND_SMS"
    const val READ_NOTIFICATION = "READ_NOTIFICATION"
    const val SET_ALARM = "SET_ALARM"
    const val CREATE_REMINDER = "CREATE_REMINDER"
    const val CANCEL_REMINDER = "CANCEL_REMINDER"
    const val MEDIA_PLAY = "MEDIA_PLAY"
    const val MEDIA_PAUSE = "MEDIA_PAUSE"
    const val MEDIA_NEXT = "MEDIA_NEXT"
    const val CONTROL_MEDIA = "CONTROL_MEDIA"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
    const val SET_VOLUME = "SET_VOLUME"
    const val BRIGHTNESS = "BRIGHTNESS"
    const val SET_BRIGHTNESS = "SET_BRIGHTNESS"
    const val TOGGLE_FLASHLIGHT = "TOGGLE_FLASHLIGHT"
    const val TOGGLE_WIFI = "TOGGLE_WIFI"
    const val TOGGLE_BLUETOOTH = "TOGGLE_BLUETOOTH"
    const val TOGGLE_MOBILE_DATA = "TOGGLE_MOBILE_DATA"
    const val TOGGLE_HOTSPOT = "TOGGLE_HOTSPOT"
    const val TOGGLE_AIRPLANE_MODE = "TOGGLE_AIRPLANE_MODE"
    const val TOGGLE_AUTO_ROTATE = "TOGGLE_AUTO_ROTATE"
    const val SET_RINGER_MODE = "SET_RINGER_MODE"
    const val TOGGLE_QUICK_SETTING = "TOGGLE_QUICK_SETTING"
    const val READ_SCREEN = "READ_SCREEN"
    const val EXPLAIN_SCREEN = "EXPLAIN_SCREEN"
    const val TAP_LIKE = "TAP_LIKE"
    const val GET_TIME = "GET_TIME"
    const val GET_DATE = "GET_DATE"
    const val GET_BATTERY = "GET_BATTERY"
    const val REMEMBER_FACT = "REMEMBER_FACT"
    const val RECALL_FACT = "RECALL_FACT"
    const val CLEAR_MEMORY = "CLEAR_MEMORY"
    const val GENERAL_CHAT = "GENERAL_CHAT"
    const val MULTI_STEP_APP_COMMAND = "MULTI_STEP_APP_COMMAND"
    const val CHECK_GMAIL = "CHECK_GMAIL"
    const val READ_GMAIL = "READ_GMAIL"
    const val REPLY_GMAIL = "REPLY_GMAIL"
    const val STOP_CANCEL = "STOP_CANCEL"
    const val DEACTIVATE_OVERLAY = "DEACTIVATE_OVERLAY"
    const val SET_VOICE = "SET_VOICE"
    const val CHANGE_VOICE = "CHANGE_VOICE"

    const val CLOSE_APP = "CLOSE_APP"
    const val CLOSE_PAGE = "CLOSE_PAGE"
    const val LIKE_POST = "LIKE_POST"
    const val COMMENT_ON_POST = "COMMENT_ON_POST"
    const val SHARE_POST = "SHARE_POST"
    const val SEARCH_IN_SCREEN = "SEARCH_IN_SCREEN"
    const val LONG_PRESS = "LONG_PRESS"
    const val CLEAR_TEXT = "CLEAR_TEXT"
    const val OPEN_KEYBOARD = "OPEN_KEYBOARD"
    const val SELECT_TEXT = "SELECT_TEXT"
    const val NEXT_VIDEO = "NEXT_VIDEO"
    const val LOCK_DEVICE = "LOCK_DEVICE"
    const val TAKE_SCREENSHOT = "TAKE_SCREENSHOT"
    const val GENERATE_IMAGE = "GENERATE_IMAGE"
    const val ANALYZE_SECURITY = "ANALYZE_SECURITY"
    const val RESEARCH_TOPIC = "RESEARCH_TOPIC"
    const val COPY_TEXT = "COPY_TEXT"
    const val PASTE_TEXT = "PASTE_TEXT"
    const val FORGET_FACT = "FORGET_FACT"
    const val UNLOCK_DEVICE = "UNLOCK_DEVICE"
    const val WAKE_SCREEN = "WAKE_SCREEN"
    const val FORCE_STOP_APP = "FORCE_STOP_APP"
    const val KILL_APP = "KILL_APP"
    const val SELECT_SIM = "SELECT_SIM"
    const val CHATGPT_GENERATE_IMAGE = "CHATGPT_GENERATE_IMAGE"
    const val DOWNLOAD_CHATGPT_IMAGE = "DOWNLOAD_CHATGPT_IMAGE"
    const val SET_WALLPAPER = "SET_WALLPAPER"
    const val SING_SONG = "SING_SONG"
    const val WHATSAPP_CALL = "WHATSAPP_CALL"
    const val OPEN_CAMERA = "OPEN_CAMERA"
    const val TAKE_PHOTO = "TAKE_PHOTO"
    const val READ_VISIBLE_CHATS = "READ_VISIBLE_CHATS"
    const val READ_SCREEN_MESSAGES = "READ_SCREEN_MESSAGES"
    const val HANDLE_CHAT_AUTHORIZATION = "HANDLE_CHAT_AUTHORIZATION"
    const val REVOKE_CHAT_AUTHORIZATION = "REVOKE_CHAT_AUTHORIZATION"
    const val SUBSCRIBE_CHANNEL = "SUBSCRIBE_CHANNEL"
    const val GET_VIDEO_TITLE = "GET_VIDEO_TITLE"
    const val GET_MEDIA_CAPTION = "GET_MEDIA_CAPTION"
    const val UNLIKE_POST = "UNLIKE_POST"
    const val OPEN_COMMENTS = "OPEN_COMMENTS"
    const val UNIVERSAL_SEARCH = "UNIVERSAL_SEARCH"
    const val SELECT_VIDEO_RESULT = "SELECT_VIDEO_RESULT"
    const val OPEN_CHAT = "OPEN_CHAT"
}
