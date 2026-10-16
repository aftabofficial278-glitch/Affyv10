package com.example.data.model

object ActionTypes {
    const val OPEN_APP = "OPEN_APP"
    const val CLICK = "CLICK"
    const val TYPE_TEXT = "TYPE_TEXT"
    const val SCROLL = "SCROLL"
    const val TAKE_SCREENSHOT = "TAKE_SCREENSHOT"
    const val PHONE_CALL = "PHONE_CALL"
    const val CHATGPT_GENERATE_IMAGE = "CHATGPT_GENERATE_IMAGE"
    const val DOWNLOAD_CHATGPT_IMAGE = "DOWNLOAD_CHATGPT_IMAGE"
    const val SET_WALLPAPER = "SET_WALLPAPER"
    const val WAIT_FOR_STATUS = "WAIT_FOR_STATUS"
}

data class AffyActionItem(
    val type: String,
    val target: String? = null,
    val text: String? = null,
    val value: String? = null,
    val delayMs: Long = 500L,
    val x: Float? = null,
    val y: Float? = null
)

data class ActionResult(
    val success: Boolean,
    val summary: String,
    val speechResponseOverride: String? = null,
    val outputData: Map<String, Any?> = emptyMap()
)
