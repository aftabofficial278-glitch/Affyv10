package com.example.agent

import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.AffyPlan
import java.util.UUID

object AffyFastCommandRouter {

    /**
     * Checks if the user speech matches a local fast-path command.
     * Returns an AffyPlan if matched with high confidence, or null if complex reasoning/Gemini is required.
     */
    fun tryFastMatch(rawSpeech: String): AffyPlan? {
        val normalized = AffySttNormalizer.normalize(rawSpeech).trim()
        if (normalized.isBlank()) return null
        val lower = normalized.lowercase()

        // 0. STOP / CANCEL / STANDBY (Immediate silence & overlay deactivation)
        if (lower.contains("off ho jao") || lower.contains("affy off") || lower == "off" ||
            lower.contains("so jao") || lower.contains("standby") || lower.contains("sleep mode") ||
            lower.contains("go to sleep") || lower.contains("deactivate") || lower.contains("overlay band") ||
            lower == "dismiss") {
            return AffyPlan(
                intent = ActionTypes.DEACTIVATE_OVERLAY,
                speechResponse = "Theek hai boss, standby mode mein hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.DEACTIVATE_OVERLAY))
            )
        }

        if (lower == "chup" || lower == "chup karo" || lower == "chup ho jao" || lower == "ruk jao" || lower == "ruko" ||
            lower == "stop" || lower == "cancel" || lower == "bas karo" || lower == "band karo" ||
            lower == "shant ho jao" || lower == "shut up" || lower == "pause" || lower == "pause karo") {
            return AffyPlan(
                intent = ActionTypes.STOP_CANCEL,
                speechResponse = "Theek hai boss, cancel kar diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.STOP_CANCEL))
            )
        }

        // 0.0 GREETINGS & ULTRA-LOW-LATENCY CONVERSATIONAL FAST-PATH
        val greetingPlan = matchGreetingOrConversational(lower, rawSpeech, normalized)
        if (greetingPlan != null) return greetingPlan

        // 0.05 VOICE SWITCHING COMMANDS (Unified AFFY Voice Settings)
        if (lower.contains("female voice") || lower.contains("ladki ki awaz") || lower.contains("female awaz") ||
            lower.contains("leda voice") || lower.contains("female voice lagao") || lower.contains("female voice karo")) {
            return AffyPlan(
                intent = ActionTypes.SET_VOICE,
                speechResponse = "Ji boss, female voice switch kar di hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOICE, target = "Leda"))
            )
        }
        if (lower.contains("male voice") || lower.contains("ladke ki awaz") || lower.contains("male awaz") ||
            lower.contains("puck voice") || lower.contains("male voice lagao") || lower.contains("male voice karo")) {
            return AffyPlan(
                intent = ActionTypes.SET_VOICE,
                speechResponse = "Ji boss, male voice switch kar di hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOICE, target = "Puck"))
            )
        }
        if (lower.contains("voice change") || lower.contains("voice badlo") || lower.contains("awaz badlo") ||
            lower.contains("awaz change") || lower.contains("change voice") || lower.contains("switch voice")) {
            return AffyPlan(
                intent = ActionTypes.SET_VOICE,
                speechResponse = "Ji boss, voice switch kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOICE, target = "toggle"))
            )
        }

        // 0.1 GMAIL COMMANDS
        if (lower.contains("mail check") || lower.contains("email check") || lower.contains("koi mail aya") ||
            lower.contains("unread email") || lower.contains("unread emails") || lower.contains("kitne email") ||
            lower.contains("emails kitni hain")) {
            return AffyPlan(
                intent = ActionTypes.CHECK_GMAIL,
                speechResponse = "Gmail check kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.CHECK_GMAIL))
            )
        }
        if (lower.contains("latest email parho") || lower.contains("sabse latest email") ||
            lower.contains("email parho") || lower.contains("email parh ke sunao")) {
            return AffyPlan(
                intent = ActionTypes.READ_GMAIL,
                speechResponse = "Latest email parh raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.READ_GMAIL))
            )
        }

        // 0.15 NOTIFICATION & WHATSAPP MESSAGE CHECKING
        val notifPlan = matchNotificationCommand(lower, normalized)
        if (notifPlan != null) return notifPlan

        // 0.2 MULTI-STEP APP COMMANDS (e.g. "YouTube par Imran Khan chalao", "Spotify par Atif Aslam play karo")
        val multiStepPlan = matchMultiStepAppCommand(lower, normalized)
        if (multiStepPlan != null) return multiStepPlan

        // 0.25 SINGER MODE COMMANDS (AFFY Singer Engine V1 - e.g. "gana gao", "sing a song", "romantic song gao")
        val singerPlan = matchSingerCommand(lower, normalized)
        if (singerPlan != null) return singerPlan

        // 0.28 CAMERA & PHOTO CAPTURE
        if (lower.contains("camera kholo aur photo") || lower.contains("camera open karke photo") ||
            lower.contains("camera khol ke photo") || lower.contains("camera khol ke tasveer") ||
            lower.contains("open camera and take photo") || lower.contains("open camera and take picture")) {
            return AffyPlan(
                intent = ActionTypes.TAKE_PHOTO,
                speechResponse = "Camera khol kar photo le raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TAKE_PHOTO, value = "open_and_capture"))
            )
        }
        if (lower == "photo lo" || lower == "photo le lo" || lower == "tasveer lo" || lower == "tasveer le lo" ||
            lower == "picture lo" || lower == "picture le lo" || lower.contains("photo kheencho") ||
            lower.contains("tasveer kheencho") || lower.contains("take photo") || lower.contains("take a photo") ||
            lower.contains("take picture") || lower.contains("take a picture") || lower.contains("capture photo") ||
            lower.contains("click photo") || lower.contains("snap photo") || lower == "photo click karo") {
            return AffyPlan(
                intent = ActionTypes.TAKE_PHOTO,
                speechResponse = "Photo capture kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TAKE_PHOTO))
            )
        }
        if (lower == "camera kholo" || lower == "camera open karo" || lower == "camera chalao" ||
            lower == "open camera" || lower == "launch camera" || lower == "camera on karo" ||
            lower == "camera khol do" || lower == "camera dikhao" || lower.contains("camera kholo")) {
            return AffyPlan(
                intent = ActionTypes.OPEN_CAMERA,
                speechResponse = "Camera khol raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.OPEN_CAMERA, target = "Camera"))
            )
        }

        // 1. OPEN APP (e.g. "AFFY YouTube kholo", "open WhatsApp", "TikTok chalao", etc.)
        val openAppPlan = matchOpenApp(lower, normalized)
        if (openAppPlan != null) return openAppPlan

        // 2. WHATSAPP MESSAGE (e.g. "Ali ko WhatsApp karo ke main 10 minute mein aa raha hoon")
        val whatsappPlan = matchWhatsAppMessage(normalized)
        if (whatsappPlan != null) return whatsappPlan

        // 3. CALL CONTACT (e.g. "Ali ko call karo", "call Hamza")
        val callPlan = matchCallContact(normalized)
        if (callPlan != null) return callPlan

        // 4. VOLUME CONTROLS
        val volumePercentMatch = Regex("(?i)(?:volume|awaz)\\s+(?:ko\\s+)?(\\d+)(?:%|\\s*percent)?").find(lower)
            ?: Regex("(?i)(\\d+)(?:%|\\s*percent)?\\s+(?:volume|awaz)").find(lower)
        if (volumePercentMatch != null) {
            val num = volumePercentMatch.groupValues[1]
            return AffyPlan(
                intent = ActionTypes.SET_VOLUME,
                speechResponse = "Volume $num% kar diya hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOLUME, value = num))
            )
        }
        if (lower.contains("volume half") || lower.contains("awaz aadhi") || lower.contains("volume 50")) {
            return AffyPlan(
                intent = ActionTypes.SET_VOLUME,
                speechResponse = "Volume 50% kar diya hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOLUME, value = "50"))
            )
        }
        if (lower.contains("volume up") || lower.contains("awaz badhao") || lower.contains("awaz barhao") ||
            lower.contains("awaz teez") || lower.contains("awaz zyada") || lower.contains("volume badhao") ||
            lower.contains("volume tez")) {
            return AffyPlan(
                intent = ActionTypes.VOLUME_UP,
                speechResponse = "Haan, awaz badha di.",
                actions = listOf(AffyActionItem(type = ActionTypes.VOLUME_UP))
            )
        }
        if (lower.contains("volume down") || lower.contains("awaz kam") || lower.contains("awaz dheemi") ||
            lower.contains("volume kam karo")) {
            return AffyPlan(
                intent = ActionTypes.VOLUME_DOWN,
                speechResponse = "Awaz kam kar di hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.VOLUME_DOWN))
            )
        }
        if (lower.contains("mute") || lower.contains("awaz band") || lower.contains("silent karo")) {
            return AffyPlan(
                intent = ActionTypes.SET_VOLUME,
                speechResponse = "Phone silent kar diya hai.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_VOLUME, value = "0"))
            )
        }

        // 5. NAVIGATION (Back, Home, Recents, Notifications)
        if (lower == "back" || lower.startsWith("back jao") || lower.contains("peeche jao") ||
            lower.contains("wapas jao") || lower.contains("go back") || lower == "peeche") {
            return AffyPlan(
                intent = ActionTypes.GO_BACK,
                speechResponse = "Theek hai, back chala gaya.",
                actions = listOf(AffyActionItem(type = ActionTypes.GO_BACK))
            )
        }
        if (lower == "home" || lower.contains("home screen") || lower.contains("home pe jao") ||
            lower.contains("main screen") || lower == "go home") {
            return AffyPlan(
                intent = ActionTypes.GO_HOME,
                speechResponse = "Home screen pe aa gaya hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.GO_HOME))
            )
        }
        if (lower.contains("recent apps") || lower.contains("recents") || lower.contains("background apps")) {
            return AffyPlan(
                intent = ActionTypes.OPEN_RECENTS,
                speechResponse = "Recent apps khol di hain.",
                actions = listOf(AffyActionItem(type = ActionTypes.OPEN_RECENTS))
            )
        }
        if (lower.contains("notifications") || lower.contains("notification kholo") || lower.contains("notif dikhao")) {
            return AffyPlan(
                intent = ActionTypes.OPEN_NOTIFICATIONS,
                speechResponse = "Notification panel open kar diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.OPEN_NOTIFICATIONS))
            )
        }

        // 5.1 DEVICE LOCK ("Affy, mobile lock kar do", "screen lock karo", "lock phone", "lock mobile", "lock device")
        if (lower.contains("mobile lock") || lower.contains("phone lock") || lower.contains("screen lock") ||
            lower.contains("lock mobile") || lower.contains("lock phone") || lower.contains("lock device") ||
            lower == "lock" || lower == "lock kar do" || lower == "lock karo" || lower == "mobile lock kar do" ||
            lower == "phone lock kar do" || lower == "screen lock kar do") {
            return AffyPlan(
                intent = ActionTypes.LOCK_DEVICE,
                speechResponse = "Ji boss, mobile lock kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.LOCK_DEVICE))
            )
        }

        // 5.15 DEVICE UNLOCK / SCREEN WAKE ("Affy, screen kholo", "screen unlock karo", "phone unlock karo", "unlock mobile", "screen on karo")
        if (lower.contains("screen kholo") || lower.contains("screen unlock") || lower.contains("phone unlock") ||
            lower.contains("mobile unlock") || lower.contains("unlock mobile") || lower.contains("unlock phone") ||
            lower.contains("unlock screen") || lower.contains("screen on karo") || lower.contains("screen wake") ||
            lower == "screen kholo" || lower == "screen open karo" || lower == "phone kholo") {
            return AffyPlan(
                intent = ActionTypes.UNLOCK_DEVICE,
                speechResponse = "Screen wake aur check kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.UNLOCK_DEVICE))
            )
        }

        // 5.2 SCREENSHOT ("screenshot lo", "screen capture karo", "take screenshot")
        if (lower.contains("screenshot") || lower.contains("screen capture")) {
            return AffyPlan(
                intent = ActionTypes.TAKE_SCREENSHOT,
                speechResponse = "Screenshot le raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TAKE_SCREENSHOT))
            )
        }

        // 5.3 SECURITY AUDIT & PERMISSION INSPECT
        if (lower.contains("security check") || lower.contains("security inspect") || lower.contains("phone safe hai") ||
            lower.contains("permissions check") || lower.contains("permissions inspect") || lower.contains("permission inspect")) {
            return AffyPlan(
                intent = ActionTypes.ANALYZE_SECURITY,
                speechResponse = "Device aur security status inspect kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.ANALYZE_SECURITY, target = normalized))
            )
        }

        // 6. FLASHLIGHT / TORCH
        if (lower.contains("torch on") || lower.contains("torch jalao") || lower.contains("flashlight on") ||
            lower.contains("batti jalao") || lower.contains("light on") || lower.contains("flash on") ||
            lower == "flash on karo" || lower == "torch on karo") {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_FLASHLIGHT,
                speechResponse = "Torch on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_FLASHLIGHT, value = "true"))
            )
        }
        if (lower.contains("torch off") || lower.contains("torch band") || lower.contains("flashlight off") ||
            lower.contains("batti bujhao") || lower.contains("light off") || lower.contains("light band") ||
            lower.contains("flash off") || lower == "flash off karo" || lower == "torch off karo") {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_FLASHLIGHT,
                speechResponse = "Torch band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_FLASHLIGHT, value = "false"))
            )
        }

        // 6.1 WI-FI TOGGLE
        if (lower.contains("wifi on") || lower.contains("wi-fi on") || lower.contains("wifi chalao") ||
            lower.contains("turn on wifi") || lower.contains("enable wifi")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_WIFI,
                speechResponse = "Wi-Fi on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_WIFI, value = "true"))
            )
        }
        if (lower.contains("wifi off") || lower.contains("wi-fi off") || lower.contains("wifi band") ||
            lower.contains("turn off wifi") || lower.contains("disable wifi")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_WIFI,
                speechResponse = "Wi-Fi band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_WIFI, value = "false"))
            )
        }

        // 6.2 MOBILE DATA TOGGLE
        if (lower.contains("data on") || lower.contains("mobile data on") || lower.contains("internet on") ||
            lower.contains("cellular data on") || lower.contains("data chalao") || lower.contains("mobile data chalao")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_MOBILE_DATA,
                speechResponse = "Mobile data on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_MOBILE_DATA, value = "true"))
            )
        }
        if (lower.contains("data off") || lower.contains("mobile data off") || lower.contains("internet off") ||
            lower.contains("cellular data off") || lower.contains("data band") || lower.contains("mobile data band") ||
            lower == "data off karo" || lower == "mobile data off karo") {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_MOBILE_DATA,
                speechResponse = "Mobile data band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_MOBILE_DATA, value = "false"))
            )
        }

        // 6.3 BLUETOOTH TOGGLE
        if (lower.contains("bluetooth on") || lower.contains("bluetooth chalao") || lower.contains("turn on bluetooth") ||
            lower.contains("enable bluetooth") || lower.contains("bt on")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_BLUETOOTH,
                speechResponse = "Bluetooth on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_BLUETOOTH, value = "true"))
            )
        }
        if (lower.contains("bluetooth off") || lower.contains("bluetooth band") || lower.contains("turn off bluetooth") ||
            lower.contains("disable bluetooth") || lower.contains("bt off")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_BLUETOOTH,
                speechResponse = "Bluetooth band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_BLUETOOTH, value = "false"))
            )
        }

        // 6.4 HOTSPOT TOGGLE
        if (lower.contains("hotspot on") || lower.contains("hotspot chalao") || lower.contains("turn on hotspot")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_HOTSPOT,
                speechResponse = "Hotspot on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_HOTSPOT, value = "true"))
            )
        }
        if (lower.contains("hotspot off") || lower.contains("hotspot band") || lower.contains("turn off hotspot")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_HOTSPOT,
                speechResponse = "Hotspot band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_HOTSPOT, value = "false"))
            )
        }

        // 6.5 AIRPLANE / FLIGHT MODE
        if (lower.contains("airplane mode on") || lower.contains("flight mode on") || lower.contains("aeroplane mode on")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_AIRPLANE_MODE,
                speechResponse = "Airplane mode on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_AIRPLANE_MODE, value = "true"))
            )
        }
        if (lower.contains("airplane mode off") || lower.contains("flight mode off") || lower.contains("aeroplane mode off") ||
            lower.contains("airplane mode band") || lower.contains("flight mode band")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_AIRPLANE_MODE,
                speechResponse = "Airplane mode band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_AIRPLANE_MODE, value = "false"))
            )
        }

        // 6.6 AUTO-ROTATE TOGGLE
        if (lower.contains("auto rotate on") || lower.contains("rotation on") || lower.contains("auto-rotate on") ||
            lower.contains("screen rotate on") || lower.contains("screen rotation on")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_AUTO_ROTATE,
                speechResponse = "Auto-rotate on kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_AUTO_ROTATE, value = "true"))
            )
        }
        if (lower.contains("auto rotate off") || lower.contains("rotation off") || lower.contains("auto-rotate off") ||
            lower.contains("auto rotate band") || lower.contains("rotation lock") || lower.contains("lock rotation")) {
            return AffyPlan(
                intent = ActionTypes.TOGGLE_AUTO_ROTATE,
                speechResponse = "Auto-rotate band kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.TOGGLE_AUTO_ROTATE, value = "false"))
            )
        }

        // 6.7 RINGER / SOUND / VIBRATE / SILENT
        if (lower.contains("silent mode") || lower.contains("phone silent") || lower.contains("mobile silent") || lower == "silent karo") {
            return AffyPlan(
                intent = ActionTypes.SET_RINGER_MODE,
                speechResponse = "Phone silent mode par set kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_RINGER_MODE, value = "silent"))
            )
        }
        if (lower.contains("vibrate mode") || lower.contains("vibration mode") || lower.contains("phone vibrate") || lower == "vibrate karo") {
            return AffyPlan(
                intent = ActionTypes.SET_RINGER_MODE,
                speechResponse = "Phone vibrate mode par set kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_RINGER_MODE, value = "vibrate"))
            )
        }
        if (lower.contains("sound mode") || lower.contains("normal mode") || lower.contains("sound on karo") || lower.contains("ringer on")) {
            return AffyPlan(
                intent = ActionTypes.SET_RINGER_MODE,
                speechResponse = "Phone normal sound mode par set kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.SET_RINGER_MODE, value = "normal"))
            )
        }

        // 7. MEDIA CONTROLS
        if (lower == "play" || lower.contains("play karo") || lower.contains("gaana bajao") ||
            lower.contains("resume") || lower.contains("music play")) {
            return AffyPlan(
                intent = ActionTypes.MEDIA_PLAY,
                speechResponse = "Music play kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.MEDIA_PLAY))
            )
        }
        if (lower == "pause" || lower.contains("pause karo") || lower.contains("gaana roko") ||
            lower.contains("stop music") || lower.contains("music roko")) {
            return AffyPlan(
                intent = ActionTypes.MEDIA_PAUSE,
                speechResponse = "Gaana pause kar diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.MEDIA_PAUSE))
            )
        }
        if (lower == "next" || lower.contains("next song") || lower.contains("agla gaana") ||
            lower.contains("skip") || lower.contains("next karo")) {
            return AffyPlan(
                intent = ActionTypes.MEDIA_NEXT,
                speechResponse = "Next track laga diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.MEDIA_NEXT))
            )
        }

        // 8. UNIVERSAL SCREEN INTERACTION ("HUMAN INSIDE THE PHONE")
        // 8.00 Positional Ordinal Screen Interaction ("3rd number wali chat open karo", "pehli chat kholo", "second option click karo", "teesra button dabao")
        val ordinalClickRegex = Regex("(?i)(?:screen\\s+par\\s+)?([0-9]+(?:st|nd|rd|th)?\\s*(?:number\\s*)?(?:wali|wala|walay|wale)?\\s*(?:chat|option|button|item|row|contact)|(?:pehli|pehla|doosri|doosra|dusri|dusra|teesri|teesra|tisri|tisra|chauthi|chautha|panchwi|panchwa|aakhri|last|top|bottom)\\s*(?:wali|wala)?\\s*(?:chat|option|button|item|row|contact))\\s*(?:ko\\s+|par\\s+)?(?:open\\s*karo|kholo|click\\s*karo|tap\\s*karo|dabao|chuno|select\\s*karo)")
        val ordinalMatch = ordinalClickRegex.find(normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), ""))
        if (ordinalMatch != null) {
            val targetOrdinal = ordinalMatch.groupValues[1].trim()
            return AffyPlan(
                intent = ActionTypes.CLICK_ELEMENT,
                speechResponse = "Screen par '$targetOrdinal' open kar raha hoon.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.CLICK_ELEMENT,
                        target = targetOrdinal
                    )
                )
            )
        }

        // 8.001 Open Specific Contact Chat ("Ali ki chat open karo", "Ali ki chat kholo", "Elon ki chat open krna")
        val cleanForChat = normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), "").trim()
        val openChatRegex = Regex("(?i)(?:screen\\s+par\\s+)?([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s*(?:ki|ke)?\\s*(?:chat|conversation)\\s*(?:ko\\s+)?(?:open\\s*karo|open\\s*krna|open\\s*kr|open\\s*kro|open\\s*kar\\s*do|kholo|khol\\s*do|kholna)")
        val openChatMatch = openChatRegex.find(cleanForChat)
        if (openChatMatch != null) {
            val contactRaw = openChatMatch.groupValues[1].trim()
            val cleanedContact = ContactResolver.cleanContactQuery(contactRaw).ifBlank { contactRaw }
            val lowerCand = cleanedContact.lowercase()
            if (cleanedContact.isNotBlank() && !listOf("is", "ye", "current", "this", "pehli", "doosri", "teesri", "dusri", "tisri", "aakhri", "last").contains(lowerCand)) {
                return AffyPlan(
                    intent = ActionTypes.OPEN_CHAT,
                    speechResponse = "$cleanedContact ki chat open kar raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.OPEN_CHAT,
                            target = cleanedContact
                        )
                    )
                )
            }
        }

        val openChatDirectRegex = Regex("(?i)(?:chat\\s*(?:open\\s*karo|open\\s*krna|open\\s*kr|open\\s*kro|open\\s*kar\\s*do|kholo|khol\\s*do)|open\\s*chat)\\s+([a-zA-Z0-9\\u0600-\\u06FF\\s]+)")
        val openDirectMatch = openChatDirectRegex.find(cleanForChat)
        if (openDirectMatch != null) {
            val contactRaw = openDirectMatch.groupValues[1].trim()
            val cleanedContact = ContactResolver.cleanContactQuery(contactRaw).ifBlank { contactRaw }
            val lowerCand = cleanedContact.lowercase()
            if (cleanedContact.isNotBlank() && !listOf("is", "ye", "current", "this", "pehli", "doosri", "teesri", "dusri", "tisri", "aakhri", "last").contains(lowerCand)) {
                return AffyPlan(
                    intent = ActionTypes.OPEN_CHAT,
                    speechResponse = "$cleanedContact ki chat open kar raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.OPEN_CHAT,
                            target = cleanedContact
                        )
                    )
                )
            }
        }

        // 8.00 Visible Chat List & Contacts Reader ("Samne kaun kaun se chats/contacts hain?", "chats dikhao", "chat list batao", "screen per kitne contact ha unke name")
        if (lower.contains("samne kaun kaun se chat") || lower.contains("samne kaun se chat") ||
            lower.contains("samne kaun si chat") || lower.contains("samne kaun kaun si chat") ||
            lower.contains("samne kaun kaun se contact") || lower.contains("samne kaun se contact") ||
            lower.contains("kaun kaun se contact") || lower.contains("kaun se contact") ||
            lower.contains("kitne contact") || lower.contains("kitne contacts") ||
            lower.contains("kitni chat") || lower.contains("kitne chat") ||
            lower.contains("contacts ke naam") || lower.contains("contact ke naam") ||
            lower.contains("contacts ke name") || lower.contains("contact ke name") ||
            (lower.contains("screen") && (lower.contains("contact") || lower.contains("contacts") || lower.contains("chats"))) ||
            lower.contains("whatsapp me kitne") || lower.contains("whatsapp par kitne") ||
            lower.contains("whatsapp ke contact") || lower.contains("whatsapp contacts") ||
            lower.contains("visible chat") || lower.contains("chat list") || lower.contains("chats ke naam") ||
            lower.contains("chats batao") || lower.contains("contacts batao") || lower.contains("chats dikhao")
        ) {
            return AffyPlan(
                intent = ActionTypes.READ_VISIBLE_CHATS,
                speechResponse = "Visible chats check kar raha hoon boss.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.READ_VISIBLE_CHATS
                    )
                )
            )
        }

        // 8.0 Open Chat Message Reader ("jo chat screen par open hai uske messages padho", "is chat ke messages suna do", "is chat mein kya message aya hai", "unke msg read krna")
        if (lower.contains("chat screen par open") || lower.contains("chat open hai") || lower.contains("is chat ke messages") ||
            lower.contains("ye chat ke messages") || lower.contains("jo samne chat") || lower.contains("is chat ko padho") ||
            lower.contains("is chat ko parho") || lower.contains("ye chat parho") || lower.contains("ye chat padho") ||
            lower.contains("chat ke messages padh") || lower.contains("chat ke messages parh") || lower.contains("chat padho") ||
            lower.contains("chat parho") || lower.contains("messages read karo") || lower.contains("messages parho") ||
            lower.contains("messages suna do") || lower.contains("messages sunao") || lower.contains("messages padh ke") ||
            lower.contains("messages parh ke") || lower.contains("jo messages screen par hain") ||
            lower.contains("is chat mein kya message") || lower.contains("is chat me kya message") ||
            lower.contains("kya message aya hai") || lower.contains("kya message hai") ||
            lower.contains("message kya aya") || lower.contains("is chat mein kya likha") ||
            lower.contains("is chat me kya likha") || lower.contains("chat mein kya aya") ||
            lower.contains("msg read") || lower.contains("msg parh") || lower.contains("msg padh") ||
            lower.contains("message read") || lower.contains("message padh") || lower.contains("message parh") ||
            lower.contains("chat ke msg") || lower.contains("chat ke message") ||
            lower.contains("unke msg") || lower.contains("unke message") || lower.contains("unke messages")
        ) {
            return AffyPlan(
                intent = ActionTypes.READ_SCREEN_MESSAGES,
                speechResponse = "Current screen ke messages parh raha hoon boss.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.READ_SCREEN_MESSAGES,
                        target = "current_chat"
                    )
                )
            )
        }

        // 8.01 Direct Send Action Trigger ("Send karo", "Ye message send karo", "Bhej do")
        if (lower == "send" || lower == "send karo" || lower == "send kar do" || lower == "send kardo" ||
            lower == "bhejo" || lower == "bhej do" || lower == "ye message send karo" || lower == "message send karo" ||
            lower == "ye message bhej do" || lower == "message bhej do" || lower == "send button dabao" || lower == "send button click karo"
        ) {
            return AffyPlan(
                intent = ActionTypes.CLICK_ELEMENT,
                speechResponse = "Message send kar raha hoon boss.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.CLICK_ELEMENT,
                        target = "send button"
                    )
                )
            )
        }

        // 8.015 Direct Post / Comment Action Trigger ("Post karo", "Comment post karo", "Ye post karo")
        if (lower == "post" || lower == "post karo" || lower == "post kar do" || lower == "comment post karo" ||
            lower == "comment post kar do" || lower == "ye post karo" || lower == "post button dabao" || lower == "post button click karo"
        ) {
            return AffyPlan(
                intent = ActionTypes.CLICK_ELEMENT,
                speechResponse = "Post button check karke submit kar raha hoon.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.CLICK_ELEMENT,
                        target = "post button"
                    )
                )
            )
        }

        // 8.02 Chat Handling Authorization ("Is chat ko handle karo", "Ali ki chat handle karo", "Is chat ko handle karna band karo")
        if (lower.contains("handle karna band") || lower.contains("handle karna roko") ||
            lower.contains("handle band karo") || lower.contains("stop handling") ||
            lower.contains("automatically reply mat karna") || lower.contains("auto reply band")
        ) {
            return AffyPlan(
                intent = ActionTypes.REVOKE_CHAT_AUTHORIZATION,
                speechResponse = "Theek hai boss, chat ka automatic reply band kar diya.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.REVOKE_CHAT_AUTHORIZATION
                    )
                )
            )
        }

        if (lower.contains("is chat ko handle karo") || lower.contains("iski chat handle karo") ||
            lower.contains("chat handle karo") || lower.contains("handle this chat") ||
            lower.contains("chat ko handle karo")
        ) {
            return AffyPlan(
                intent = ActionTypes.HANDLE_CHAT_AUTHORIZATION,
                speechResponse = "Ji boss, is chat ko handle kar raha hoon. Automatically reply bhej doon ga.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.HANDLE_CHAT_AUTHORIZATION,
                        target = "current_chat"
                    )
                )
            )
        }

        // 8.05 Compound Type & Send on Current Chat ("Isme hello type karke send karo", "isme ... type karke bhej do", "ye likh ke send kardo")
        val typeAndSendRegex = Regex("(?i)(?:isme|is chat mein|yahan|screen par)?\\s*(?:ye|yeh)?\\s*['\"]?(.+?)['\"]?\\s*(?:type|likh)\\s*(?:karke|ke|kar)?\\s*(?:send|bhej)\\s*(?:karo|do|kar do|karna)")
        val typeAndSendMatch = typeAndSendRegex.find(normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), ""))
        if (typeAndSendMatch != null) {
            val textToSend = typeAndSendMatch.groupValues[1].replace(Regex("(?i)^(?:isme|is chat mein|yahan|ye|yeh)\\s*"), "").trim(' ', '"', '\'')
            if (textToSend.isNotBlank() && !textToSend.equals("message", ignoreCase = true) && !textToSend.equals("text", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    speechResponse = "Ji boss, '$textToSend' type karke bhej raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = "current_chat",
                            text = textToSend
                        )
                    )
                )
            }
        }

        // 8.1 Read / Explain Screen
        if (lower.contains("screen pe kya") || lower.contains("screen par kya") || lower.contains("meri screen par kya hai") ||
            lower.contains("screen pe kya dikh raha hai") || lower.contains("screen scan karo") || lower.contains("kya likha hai") ||
            lower.contains("yahan kya option") || lower.contains("options kya hain") || lower.contains("screen padho") ||
            lower.contains("screen read") || lower.contains("read screen") || lower.contains("screen dekho") ||
            lower.contains("what's on screen") || lower.contains("what is on the screen") || lower.contains("screen par jo hai parho")) {
            return AffyPlan(
                intent = ActionTypes.READ_SCREEN,
                speechResponse = "Screen scan kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.READ_SCREEN))
            )
        }

        // 8.2 Type / Keyboard on Screen
        val typeRegex = Regex("(?i)(?:keyboard\\s+(?:open|kholo)\\s+aur\\s+)?(?:ye\\s+)?(?:code|text|message)?\\s*(?:likho|type\\s*karo|write)\\s*[:\\-]?\\s*(.+)")
        val typeMatch = typeRegex.find(normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), ""))
        if (typeMatch != null) {
            val textToType = typeMatch.groupValues[1].trim(' ', '"', '\'')
            if (textToType.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.TYPE_TEXT,
                    speechResponse = "Ji boss, screen par likh raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.TYPE_TEXT,
                            text = textToType
                        )
                    )
                )
            }
        }

        // 8.3 Close App / Page / Dialog
        val closeRegex = Regex("(?i)(?:ye\\s+)?([a-zA-Z0-9\\s]+?)\\s*(?:ko\\s+)?(?:close\\s*karo|band\\s*karo|exit\\s*karo|hatao)")
        val closeMatch = closeRegex.find(normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), ""))
        if (closeMatch != null) {
            val rawTarget = closeMatch.groupValues[1].trim()
            if (rawTarget.isNotBlank() && !rawTarget.equals("awaz", ignoreCase = true) && !rawTarget.equals("volume", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.CLOSE_APP,
                    speechResponse = "Theek hai boss, $rawTarget close kar raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.CLOSE_APP,
                            target = rawTarget
                        )
                    )
                )
            }
        }

        // 8.35 Keyboard & Typing Commands ("keyboard open karo aur ye likho...", "keyboard kholo", "likho ...")
        if (lower.contains("keyboard open") || lower.contains("keyboard kholo")) {
            val typeText = if (lower.contains("aur") || lower.contains("likho") || lower.contains("type")) {
                normalized.replace(Regex("(?i).*?\\b(likho|type karo|write)\\b"), "").trim()
            } else ""
            return if (typeText.isNotBlank()) {
                AffyPlan(
                    intent = ActionTypes.TYPE_TEXT,
                    speechResponse = "Keyboard open karke likh raha hoon.",
                    actions = listOf(
                        AffyActionItem(type = ActionTypes.OPEN_KEYBOARD),
                        AffyActionItem(type = ActionTypes.TYPE_TEXT, text = typeText)
                    )
                )
            } else {
                AffyPlan(
                    intent = ActionTypes.OPEN_KEYBOARD,
                    speechResponse = "Keyboard open kar raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.OPEN_KEYBOARD))
                )
            }
        }

        if (lower.startsWith("likho ") || lower.startsWith("type karo ") || lower.contains("ye likho ") || lower.contains("yeh likho ")) {
            val typeText = normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)?\\s*(?:ye|yeh)?\\s*(?:likho|type karo|type)\\s*"), "").trim()
            if (typeText.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.TYPE_TEXT,
                    speechResponse = "Likh raha hoon.",
                    actions = listOf(AffyActionItem(type = ActionTypes.TYPE_TEXT, text = typeText))
                )
            }
        }

        // 8.4 Click / Tap UI Elements on Screen ("Affy screen par jo red button hai usay click karo", "laal button dabao", "second option select karo")
        val clickRegex = Regex("(?i)(?:screen\\s+par\\s+(?:jo\\s+)?)?(.+?)(?:\\s+(?:hai\\s+)?(?:usay\\s+|isay\\s+|ko\\s+)?)?\\s*(?:par\\s+)?(?:click\\s*karo|tap\\s*karo|dabao|chuno|select\\s*karo|press\\s*karo)")
        val cleanCmd = normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), "").trim()
        if (lower.contains("click") || lower.contains("tap") || lower.contains("dabao") || lower.contains("press") || lower.contains("select karo")) {
            val clickMatch = clickRegex.find(cleanCmd)
            val extractedTarget = clickMatch?.groupValues?.getOrNull(1)?.trim() ?: cleanCmd
            if (extractedTarget.isNotBlank() && !extractedTarget.equals("video", ignoreCase = true) && !extractedTarget.equals("post", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.CLICK_ELEMENT,
                    speechResponse = "Screen par '$extractedTarget' check karke click kar raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.CLICK_ELEMENT,
                            target = extractedTarget
                        )
                    )
                )
            }
        }

        // 8.45 Media Intelligence Commands (YouTube, TikTok, Reels)
        // 8.45.1 Video Title
        if (lower.contains("video ka naam") || lower.contains("title kya hai") || lower.contains("is video ka title") ||
            lower.contains("video title") || lower.contains("title batao") || lower.contains("video ka title")) {
            return AffyPlan(
                intent = ActionTypes.GET_VIDEO_TITLE,
                speechResponse = "Video ka title check kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.GET_VIDEO_TITLE))
            )
        }

        // 8.45.2 Video Caption / Current Video
        if (lower.contains("current video batao") || lower.contains("ye video kya hai") || lower.contains("caption parho") ||
            lower.contains("caption padho") || lower.contains("caption sunao") || lower.contains("caption kya hai")) {
            return AffyPlan(
                intent = ActionTypes.GET_MEDIA_CAPTION,
                speechResponse = "Caption check kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.GET_MEDIA_CAPTION))
            )
        }

        // 8.45.3 Video Result Selection ("first video open karo", "2nd video chalao", "3rd video play karo")
        val videoSelectRegex = Regex("(?i)(?:screen\\s+par\\s+)?([0-9]+(?:st|nd|rd|th)?\\s*(?:number\\s*)?(?:wali|wala|walay|wale)?\\s*(?:video|result)|(?:pehli|pehla|doosri|doosra|dusri|dusra|teesri|teesra|tisri|tisra|chauthi|chautha|panchwi|panchwa)\\s*(?:wali|wala)?\\s*video)\\s*(?:ko\\s+|par\\s+)?(?:open\\s*karo|kholo|chalao|play\\s*karo|laga\\s*do|select\\s*karo)")
        val videoSelectMatch = videoSelectRegex.find(normalized.replace(Regex("(?i)^(?:affy|hey affy|suno affy)\\s*"), ""))
        if (videoSelectMatch != null) {
            val ordStr = videoSelectMatch.groupValues[1].lowercase()
            val ordNum = when {
                ordStr.contains("1") || ordStr.contains("first") || ordStr.contains("pehli") || ordStr.contains("pehla") -> 1
                ordStr.contains("2") || ordStr.contains("second") || ordStr.contains("doosri") || ordStr.contains("doosra") || ordStr.contains("dusri") -> 2
                ordStr.contains("3") || ordStr.contains("third") || ordStr.contains("teesri") || ordStr.contains("teesra") || ordStr.contains("tisri") -> 3
                ordStr.contains("4") || ordStr.contains("fourth") || ordStr.contains("chauthi") || ordStr.contains("chautha") -> 4
                ordStr.contains("5") || ordStr.contains("fifth") || ordStr.contains("panchwi") || ordStr.contains("panchwa") -> 5
                else -> 1
            }
            return AffyPlan(
                intent = ActionTypes.SELECT_VIDEO_RESULT,
                speechResponse = "Video #$ordNum open kar raha hoon.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.SELECT_VIDEO_RESULT,
                        value = ordNum.toString()
                    )
                )
            )
        }

        // 8.45.4 Subscribe Channel
        if (lower.contains("subscribe karo") || lower.contains("channel subscribe") || lower == "subscribe") {
            return AffyPlan(
                intent = ActionTypes.SUBSCRIBE_CHANNEL,
                speechResponse = "Subscribe button check kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.SUBSCRIBE_CHANNEL))
            )
        }

        // 8.45.5 Unlike Post / Video
        if (lower.contains("unlike karo") || lower.contains("like hata do") || lower.contains("unlike this") || lower == "unlike") {
            return AffyPlan(
                intent = ActionTypes.UNLIKE_POST,
                speechResponse = "Like remove kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.UNLIKE_POST))
            )
        }

        // 8.45.6 Comment Section & Comment Writing
        if (lower.contains("comment section kholo") || lower.contains("comments kholo") || lower.contains("comment kholo") || lower.contains("comments dikhao")) {
            return AffyPlan(
                intent = ActionTypes.OPEN_COMMENTS,
                speechResponse = "Comment section open kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.OPEN_COMMENTS))
            )
        }

        if (lower.contains("comment mein likho") || lower.contains("comment likho") || lower.contains("comment karo")) {
            val commentText = normalized.replace(Regex("(?i).*?\\b(comment mein likho|comment likho|comment karo)[:\\s]*"), "").trim(' ', '"', '\'')
            if (commentText.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.COMMENT_ON_POST,
                    speechResponse = "Comment post kar raha hoon boss.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.COMMENT_ON_POST,
                            text = commentText
                        )
                    )
                )
            }
        }

        // 8.45.7 Universal Search Field ("search box mein ChatGPT likho", "screen par search karo ...")
        if (lower.contains("search box mein") || lower.contains("search bar mein")) {
            val queryText = normalized.replace(Regex("(?i).*?\\b(search box mein|search bar mein)\\s*(?:likho|type karo)?\\s*"), "").trim(' ', '"', '\'')
            if (queryText.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.UNIVERSAL_SEARCH,
                    speechResponse = "Search box mein '$queryText' type karke search kar raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.UNIVERSAL_SEARCH,
                            text = queryText
                        )
                    )
                )
            }
        }

        // 8.5 Scroll Down / Up / Reels
        if (lower.contains("next reel") || lower.contains("agla reel") || lower.contains("agli reel") ||
            lower.contains("agla video") || lower.contains("swipe up") || lower.contains("reel change") ||
            lower.contains("scroll down") || lower.contains("neeche scroll") || lower.contains("neeche jao") ||
            lower.contains("neeche karo")) {
            return AffyPlan(
                intent = ActionTypes.SCROLL_DOWN,
                speechResponse = "Done boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_DOWN))
            )
        }
        if (lower.contains("previous reel") || lower.contains("pichli reel") || lower.contains("pichla reel") ||
            lower.contains("swipe down") || lower.contains("scroll up") || lower.contains("upar scroll") ||
            lower.contains("upar jao") || lower.contains("upar karo")) {
            return AffyPlan(
                intent = ActionTypes.SCROLL_UP,
                speechResponse = "Upar scroll kar diya.",
                actions = listOf(AffyActionItem(type = ActionTypes.SCROLL_UP))
            )
        }
        if (lower.contains("like karo") || lower.contains("heart dabao") || lower.contains("like this") ||
            lower.contains("pasand karo") || lower.contains("video like")) {
            return AffyPlan(
                intent = ActionTypes.TAP_LIKE,
                speechResponse = "Video like kar di ❤️",
                actions = listOf(AffyActionItem(type = ActionTypes.TAP_LIKE))
            )
        }

        // 9. QUICK INFO (Time, Date, Battery)
        if (lower.contains("time kya") || lower.contains("kitne baje") || lower.contains("what time") ||
            lower == "time" || lower.contains("waqt kya") || lower.contains("time kitna hua") || lower.contains("time batao")) {
            return AffyPlan(
                intent = ActionTypes.GET_TIME,
                speechResponse = "Abhi ka waqt check kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.GET_TIME))
            )
        }
        if (lower.contains("date kya") || lower.contains("aaj ki date") || lower.contains("tarikh kya") ||
            lower.contains("today's date")) {
            return AffyPlan(
                intent = ActionTypes.GET_DATE,
                speechResponse = "Aaj ki date check kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.GET_DATE))
            )
        }
        if (lower.contains("battery") || lower.contains("charging") || lower.contains("charge kitna") ||
            lower.contains("kitna charge") || lower.contains("kitni battery") || lower.contains("charging ho rahi")) {
            return AffyPlan(
                intent = ActionTypes.GET_BATTERY,
                speechResponse = "Battery status check kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.GET_BATTERY))
            )
        }

        // 10. LONG-TERM MEMORY COMMANDS (SURVIVES LIVE RESTARTS)
        // 10.1 "Affy, ye yaad rakhna..." / "ye yaad rakho..." / "remember this..." / "Remember my name is Aftab" / "Remember Arham is my friend"
        val isExplicitRemember = (lower.startsWith("remember ") || lower.startsWith("affy remember ") ||
                lower.contains("remember that") || lower.contains("remember this") || lower.contains("remember to") ||
                lower.contains("remember my name") || lower.contains("remember it") || lower.contains("remember:") ||
                lower.contains("yaad rakhna") || lower.contains("yaad rakho") || lower.contains("yaad rakh") ||
                lower.contains("yaad rahe") || lower.contains("memory mein save") || lower.contains("memory me save") ||
                lower.contains("save in memory") || lower.contains("save to memory") || lower.contains("save this") ||
                lower.contains("note kar lo") || lower.contains("note down") ||
                (lower.contains("remember") && !lower.contains("what do you remember") && !lower.contains("do you remember") && !lower.contains("yaad hai"))) &&
                !lower.contains("kya yaad") && !lower.contains("what do you remember")

        if (isExplicitRemember) {
            return AffyPlan(
                intent = ActionTypes.REMEMBER_FACT,
                speechResponse = "Ji boss, yeh baat long-term memory mein save kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.REMEMBER_FACT, text = normalized))
            )
        }

        // 10.2 "Affy, ye bhool jao..." / "forget this..." / "is memory ko delete karo" / "jo tumne mere bare mein save kiya hai wo delete karo"
        if (lower.contains("bhool jao") || lower.contains("forget this") || lower.contains("memory se hata") ||
            lower.contains("delete memory") || lower.contains("is memory ko delete") || lower.contains("memory delete") ||
            (lower.contains("delete karo") && lower.contains("save kiya"))) {
            return AffyPlan(
                intent = ActionTypes.FORGET_FACT,
                speechResponse = "Ji boss, memory se remove kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.FORGET_FACT, target = normalized))
            )
        }

        // 10.3 "Affy, pichli baat yaad hai?" / "kya yaad hai?" / "mere bare mein kya yaad hai?" / "affy yaad hai?"
        if (lower.contains("pichli baat yaad") || lower.contains("kya yaad hai") || lower.contains("kya kya yaad") ||
            lower.contains("mere bare mein kya yaad") || lower.contains("mere bare me kya yaad") ||
            lower.contains("purani baat yaad") || lower.contains("purani baatein yaad") || lower.contains("what do you remember") ||
            lower == "affy yaad hai" || lower == "yaad hai" || lower == "yaad hai?") {
            return AffyPlan(
                intent = ActionTypes.RECALL_FACT,
                speechResponse = "Memory recall kar raha hoon boss.",
                actions = listOf(AffyActionItem(type = ActionTypes.RECALL_FACT, target = normalized))
            )
        }

        // 10.4 Clear all memory
        if (lower.contains("saari memory clear") || lower.contains("clear all memory") || lower.contains("saari baatein bhool jao")) {
            return AffyPlan(
                intent = ActionTypes.CLEAR_MEMORY,
                speechResponse = "Ji boss, memory clear kar raha hoon.",
                actions = listOf(AffyActionItem(type = ActionTypes.CLEAR_MEMORY))
            )
        }

        // No simple fast path -> Delegate to Gemini reasoning
        return null
    }

    private fun matchOpenApp(lower: String, originalNormalized: String): AffyPlan? {
        val knownApps = listOf(
            "YouTube", "WhatsApp", "TikTok", "Instagram", "Facebook",
            "Spotify", "Chrome", "Camera", "Gallery", "Settings",
            "Calculator", "Clock", "Maps", "Gmail"
        )

        // If the utterance has search/play/multi-step keywords, do NOT hijack it as a pure OPEN_APP
        val multiStepIndicators = listOf("search", "dhoondo", "dhoond", "play", "bhejo", "send", "call", "aur", "then", "karke")
        if (multiStepIndicators.any { lower.contains(it) }) {
            return null
        }

        // Exact app name utterance (e.g. "YouTube", "WhatsApp")
        for (app in knownApps) {
            if (lower == app.lowercase() || originalNormalized.equals(app, ignoreCase = true)) {
                AffyDiagnostics.logPipelineIntent(targetApp = app, requestedAction = "OPEN_APP", query = "")
                return AffyPlan(
                    intent = ActionTypes.OPEN_APP,
                    speechResponse = "Haan, $app khol raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.OPEN_APP,
                            target = app
                        )
                    )
                )
            }
        }

        val openVerbs = listOf("kholo", "khol do", "chalao", "chala do", "open karo", "open", "kholiye", "khol de", "uughada", "kholyo", "khol")
        val hasOpenVerb = openVerbs.any { lower.contains(it) }

        if (!hasOpenVerb && !lower.startsWith("open ") && !lower.startsWith("launch ")) {
            return null
        }

        for (app in knownApps) {
            if (originalNormalized.contains(app, ignoreCase = true)) {
                AffyDiagnostics.logPipelineIntent(targetApp = app, requestedAction = "OPEN_APP", query = "")
                return AffyPlan(
                    intent = ActionTypes.OPEN_APP,
                    speechResponse = "Haan, $app khol raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.OPEN_APP,
                            target = app
                        )
                    )
                )
            }
        }

        // Generic "open [App]"
        val openPrefixRegex = Regex("(?i)^(open|launch|kholo)\\s+([a-zA-Z0-9]+)$")
        val match = openPrefixRegex.find(originalNormalized)
        if (match != null) {
            val app = match.groupValues[2].trim()
            AffyDiagnostics.logPipelineIntent(targetApp = app, requestedAction = "OPEN_APP", query = "")
            return AffyPlan(
                intent = ActionTypes.OPEN_APP,
                speechResponse = "Haan, $app khol raha hoon.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.OPEN_APP,
                        target = app
                    )
                )
            )
        }

        return null
    }

    private fun cleanWhatsAppPrefix(raw: String): String {
        var msg = raw.trim()
        val prefixes = listOf("ke ", "ki ", "that ", "saying ")
        for (prefix in prefixes) {
            if (msg.startsWith(prefix, ignoreCase = true)) {
                msg = msg.substring(prefix.length).trim()
            }
        }
        return msg
    }

    private fun matchWhatsAppMessage(speech: String): AffyPlan? {
        // e.g. "Ali ko WhatsApp karo ke main 10 minute mein aa raha hoon"
        // e.g. "Ali ko msg karo meeting cancel ho gayi hai"
        // e.g. "WhatsApp Ali that I am coming"
        // e.g. "Send message to Ali saying hello"
        val clean = speech.trim()

        // Pattern 1: "[Contact] ko (whatsapp|message|msg|text|likho|bol do) (karo|bhejo|kar do|bhej do) (ke|ki|that)? [Message]"
        val p1 = Regex("(?i)^([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s+ko\\s+(?:whatsapp|message|msg|text|likho|bol do)\\s*(?:karo|bhejo|kar do|bhej do)?\\s*(?:ke|ki|that)?\\s*(.+)$")
        val m1 = p1.find(clean)
        if (m1 != null) {
            val contact = m1.groupValues[1].trim()
            val message = cleanWhatsAppPrefix(m1.groupValues[2].trim())
            if (contact.isNotBlank() && message.isNotBlank() && !contact.equals("mujhe", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    speechResponse = "Ji boss, $contact ko WhatsApp bhej raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = contact,
                            text = message
                        )
                    )
                )
            }
        }

        // Pattern 2: "(whatsapp|message|msg|text) [Contact] (that|ke|ki|saying)? [Message]"
        val p2 = Regex("(?i)^(?:whatsapp|message|msg|text)\\s+([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s+(?:that|ke|ki|saying|ko)?\\s*(.+)$")
        val m2 = p2.find(clean)
        if (m2 != null) {
            val contact = m2.groupValues[1].trim()
            val message = cleanWhatsAppPrefix(m2.groupValues[2].trim())
            if (contact.isNotBlank() && message.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    speechResponse = "Ji boss, $contact ko WhatsApp bhej raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = contact,
                            text = message
                        )
                    )
                )
            }
        }

        // Pattern 3: "(send|bhejo) (whatsapp|message|msg|text) to [Contact] (saying|that)? [Message]"
        val p3 = Regex("(?i)^(?:send|bhejo)\\s+(?:whatsapp|message|msg|text)\\s+to\\s+([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s+(?:saying|that|ke|ki)?\\s*(.+)$")
        val m3 = p3.find(clean)
        if (m3 != null) {
            val contact = m3.groupValues[1].trim()
            val message = cleanWhatsAppPrefix(m3.groupValues[2].trim())
            if (contact.isNotBlank() && message.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    speechResponse = "Ji boss, $contact ko WhatsApp bhej raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = contact,
                            text = message
                        )
                    )
                )
            }
        }

        return null
    }

    private fun matchCallContact(speech: String): AffyPlan? {
        val clean = speech.trim()

        // 0. WhatsApp Call Pattern: "[Contact] ko whatsapp call karo" or "whatsapp call [Contact]"
        val waCallPattern1 = Regex("(?i)^([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s+ko\\s+whatsapp\\s+(?:voice\\s+)?call\\s*(?:karo|lagao|milao|kar do|laga do|mila do)?$")
        val waMatch1 = waCallPattern1.find(clean)
        if (waMatch1 != null) {
            val contact = waMatch1.groupValues[1].trim()
            if (contact.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.WHATSAPP_CALL,
                    speechResponse = "Haan, $contact ko WhatsApp call mila raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.WHATSAPP_CALL,
                            target = contact
                        )
                    )
                )
            }
        }

        val waCallPattern2 = Regex("(?i)^whatsapp\\s+(?:voice\\s+)?call\\s*(?:karo|lagao|milao|kar do|laga do|mila do)?\\s*([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)(?:\\s+ko)?$")
        val waMatch2 = waCallPattern2.find(clean)
        if (waMatch2 != null) {
            val contact = waMatch2.groupValues[1].trim()
            if (contact.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.WHATSAPP_CALL,
                    speechResponse = "Haan, $contact ko WhatsApp call mila raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.WHATSAPP_CALL,
                            target = contact
                        )
                    )
                )
            }
        }

        // Pattern 1: "[Contact] ko (call|phone) (karo|lagao|milao|kar do|laga do|mila do|karna|lagana)"
        val p1 = Regex("(?i)^([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)\\s+ko\\s+(?:call|phone)\\s*(?:karo|lagao|milao|kar do|laga do|mila do|karna|lagana)?$")
        val m1 = p1.find(speech)
        if (m1 != null) {
            val contact = m1.groupValues[1].trim()
            if (contact.isNotBlank()) {
                return AffyPlan(
                    intent = ActionTypes.CALL_CONTACT,
                    speechResponse = "Haan, $contact ko call mila raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.CALL_CONTACT,
                            target = contact
                        )
                    )
                )
            }
        }

        // Pattern 2: "(call|phone) (karo|lagao|milao) [Contact] (ko)?"
        val p2 = Regex("(?i)^(?:call|phone)\\s+(?:karo|lagao|milao|kar do|laga do|mila do)?\\s*([a-zA-Z0-9\\u0600-\\u06FF\\s]+?)(?:\\s+ko)?$")
        val m2 = p2.find(speech)
        if (m2 != null) {
            val contact = m2.groupValues[1].trim()
            if (contact.isNotBlank() && !contact.equals("phone", ignoreCase = true) && !contact.equals("dialer", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.CALL_CONTACT,
                    speechResponse = "Haan, $contact ko call mila raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.CALL_CONTACT,
                            target = contact
                        )
                    )
                )
            }
        }

        // Pattern 3: "call [Contact]" or "dial [Number/Contact]"
        val p3 = Regex("(?i)^(?:call|dial)\\s+([a-zA-Z0-9\\u0600-\\u06FF\\s+]+)$")
        val m3 = p3.find(speech)
        if (m3 != null) {
            val contact = m3.groupValues[1].trim()
            if (contact.isNotBlank() && !contact.equals("phone", ignoreCase = true) && !contact.equals("dialer", ignoreCase = true)) {
                return AffyPlan(
                    intent = ActionTypes.CALL_CONTACT,
                    speechResponse = "Haan, $contact ko call mila raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.CALL_CONTACT,
                            target = contact
                        )
                    )
                )
            }
        }

        // Pattern 4: Direct phone numbers (e.g. "03001234567 ko call", "03001234567")
        val digitsOnly = speech.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && (speech.contains("call") || speech.contains("phone") || speech.contains("dial") || speech.startsWith("+") || speech.startsWith("03"))) {
            return AffyPlan(
                intent = ActionTypes.CALL_CONTACT,
                speechResponse = "Haan, call mila raha hoon.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.CALL_CONTACT,
                        target = digitsOnly
                    )
                )
            )
        }

        return null
    }

    private fun matchMultiStepAppCommand(lower: String, normalized: String): AffyPlan? {
        // e.g. "YouTube par Imran Khan chalao" / "YouTube par Imran Khan search karo" / "Spotify par Atif Aslam play karo"
        val apps = listOf("youtube", "spotify", "chrome", "maps", "gaana")
        for (app in apps) {
            if (lower.contains(app)) {
                // Check for playback or search verbs (longer phrases first)
                val verbs = listOf("search karo", "search kar do", "dhoondo", "dhoond do", "khoj karo", "search", "chalao", "chala do", "play karo", "laga do", "lagao")
                for (verb in verbs) {
                    if (lower.contains(verb)) {
                        val query = normalized
                            .replace(Regex("(?i)\\b$app\\b"), "")
                            .replace(Regex("(?i)\\bpar\\b"), "")
                            .replace(Regex("(?i)\\bpe\\b"), "")
                            .replace(Regex("(?i)\\bon\\b"), "")
                            .replace(Regex("(?i)\\b$verb\\b"), "")
                            .replace(Regex("(?i)\\baffy\\b"), "")
                            .replace(Regex("(?i)\\bplease\\b"), "")
                            .trim()

                        if (query.isNotBlank() && query.length >= 2) {
                            val isSearch = verb.contains("search") || verb.contains("dhoond") || verb.contains("khoj") || lower.contains("search") || lower.contains("dhoondo")
                            val appDisplayName = app.replaceFirstChar { it.uppercase() }
                            val actionValue = if (isSearch) "search" else "play"
                            val speechText = if (isSearch) {
                                "Haan, $appDisplayName par '$query' search kar raha hoon."
                            } else {
                                "Haan, $appDisplayName par '$query' play kar raha hoon."
                            }

                            AffyDiagnostics.logPipelineIntent(
                                targetApp = appDisplayName,
                                requestedAction = if (isSearch) "SEARCH" else "PLAY",
                                query = query
                            )

                            return AffyPlan(
                                intent = ActionTypes.MULTI_STEP_APP_COMMAND,
                                speechResponse = speechText,
                                actions = listOf(
                                    AffyActionItem(
                                        type = ActionTypes.MULTI_STEP_APP_COMMAND,
                                        target = appDisplayName,
                                        text = query,
                                        value = actionValue
                                    )
                                )
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private fun matchGreetingOrConversational(lower: String, rawSpeech: String, normalized: String): AffyPlan? {
        val clean = lower.trim().replace(Regex("[.,!?;:]"), "").trim()

        // 1. Standalone Wake-words & Direct Greetings
        // "hello affy", "hello aftab", "hi affy", "hi aftab", "hey affy", "hey aftab", "hello", "hi", "hey", "affy", "aftab"
        if (clean == "hello" || clean == "hi" || clean == "hey" ||
            clean == "hello affy" || clean == "hi affy" || clean == "hey affy" ||
            clean == "hello aftab" || clean == "hi aftab" || clean == "hey aftab" ||
            clean == "affy" || clean == "aftab" || clean == "aftaab" ||
            clean == "oye affy" || clean == "suno affy" || clean == "bhai affy" ||
            clean == "oye aftab" || clean == "suno aftab" || clean == "bhai aftab" ||
            clean == "effy" || clean == "affi" || clean == "afy" || clean == "afi") {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Ji boss 😄 bolo.",
                actions = emptyList()
            )
        }

        // 2. Islamic Greetings
        // "assalam o alaikum", "assalamu alaikum", "salam", "slam", "walaikum assalam"
        if (clean.contains("assalam") || clean.contains("salam") || clean.contains("slam")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Walaikum Assalam boss! Kahiye kya hukum hai?",
                actions = emptyList()
            )
        }

        // 3. Health / Wellness inquiry
        // "kaise ho affy", "kaise ho", "kya haal hai", "kese ho", "how are you", "sab theek"
        if (clean.contains("kaise ho") || clean.contains("kese ho") || clean.contains("kya haal") ||
            clean.contains("how are you") || clean.contains("sab theek") || clean.contains("tabiyat kaisi")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Main bilkul theek hoon boss 😄 aap sunao.",
                actions = emptyList()
            )
        }

        // 4. Attention / Listening check
        // "sun rahe ho affy", "sun rahe ho", "suno na", "are you listening"
        if (clean.contains("sun rahe ho") || clean.contains("sun rahe") || clean.contains("are you listening") ||
            clean.contains("sun b rahe ho") || clean.contains("awaz aa rahi hai") || clean.contains("meri awaz")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Ji boss, bilkul sun raha hoon. Hukum karein.",
                actions = emptyList()
            )
        }

        // 5. Conversational readiness
        // "acha ek kaam karo", "ek kaam karo", "suno ek kaam hai", "ek kaam tha"
        if (clean.contains("ek kaam karo") || clean.contains("ek kaam hai") || clean.contains("ek kaam tha") ||
            clean.contains("madad chahiye") || clean.contains("meri baat suno")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Ji boss, bataiye kya karna hai?",
                actions = emptyList()
            )
        }

        // 6. Courtesy & Time Greetings
        if (clean.contains("good morning") || clean.contains("subah bakhair")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Good morning boss! Aaj kya plan hai?",
                actions = emptyList()
            )
        }
        if (clean.contains("good night") || clean.contains("shabba khair")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Good night boss! Aaram karein.",
                actions = emptyList()
            )
        }
        if (clean.contains("good afternoon") || clean.contains("good evening")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Good day boss! Kahiye kya karoon?",
                actions = emptyList()
            )
        }

        // 7. Gratitude
        if (clean.contains("shukriya") || clean.contains("thank you") || clean.contains("thanks") || clean.contains("meherbani")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Koi baat nahi boss, mera kaam hi yahi hai.",
                actions = emptyList()
            )
        }

        // 8. Identity, Creator & Capabilities
        if (clean.contains("naam affy kyun") || clean.contains("naam affy kyu") || clean.contains("affy naam kese") ||
            clean.contains("affy naam kaise") || clean.contains("naam affy kisne") || clean.contains("why are you called affy") ||
            clean.contains("why your name is affy") || clean.contains("why is your name affy") || clean.contains("affy naam kyu") ||
            clean.contains("affy naam kyun") || clean.contains("affy name reason") || clean.contains("affy kyu rakha") ||
            clean.contains("affy kyun rakha") || clean.contains("naam ka matlab")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Mera naam mere creator Aftab ke nickname 'Affy' par rakha gaya hai. Aftab ne apna nickname mujhe diya, is liye mera naam AFFY hai.",
                actions = emptyList()
            )
        }

        if (clean.contains("aftab kahan") || clean.contains("aftab kidhar") || clean.contains("where is aftab") ||
            clean.contains("creator kahan") || clean.contains("creator kidhar") || clean.contains("where is your creator") ||
            clean.contains("where does aftab live") || clean.contains("aftab rehta kahan") || clean.contains("aftab ki location")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Mere creator Aftab Jacobabad, Sindh, Pakistan se hain.",
                actions = emptyList()
            )
        }

        if (clean.contains("kisne banaya") || clean.contains("who made you") || clean.contains("who created you") ||
            clean.contains("who developed you") || clean.contains("who designed you") || clean.contains("your owner") ||
            clean.contains("tumhe kisne banaya") || clean.contains("kiska assistant") || clean.contains("kiske assistant ho") ||
            clean.contains("creator kaun") || clean.contains("developer kaun") || clean.contains("aapko kisne banaya") ||
            clean.contains("tera malik kaun") || clean.contains("kisne create kiya")) {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Mujhe Aftab ne develop kiya hai jo Jacobabad, Sindh, Pakistan se hain. Main Aftab ka personal AI assistant hoon.",
                actions = emptyList()
            )
        }
        if (clean.contains("kaun ho tum") || clean.contains("tum kaun ho") || clean.contains("who are you") ||
            clean.contains("naam kya hai") || clean.contains("apna intro") || clean.contains("apna taruf") ||
            clean == "affy tum kaun ho" || clean == "affy who are you") {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Main AFFY hoon boss, aapka personal smart AI assistant.",
                actions = emptyList()
            )
        }
        if (clean.contains("kya kar sakte ho") || clean.contains("what can you do") || clean == "help" || clean == "madad") {
            return AffyPlan(
                intent = ActionTypes.GENERAL_CHAT,
                speechResponse = "Boss, main apps khol sakta hoon, WhatsApp messages bhej sakta hoon, YouTube ya Spotify chala sakta hoon, volume aur flashlight control kar sakta hoon.",
                actions = emptyList()
            )
        }

        // 9. Short Conversational Acknowledgements (Only if no pending notification awaiting confirmation)
        val hasUnreadNotifs = AffyConversationContextManager.getInstance().getUnreadNotifications().isNotEmpty()
        if (!hasUnreadNotifs) {
            if (clean == "acha" || clean == "theek hai" || clean == "sahi hai" || clean == "ok" || clean == "okay" ||
                clean == "haan" || clean == "haan boss" || clean == "hmmm" || clean == "hmm" || clean == "bilkul" || clean == "samjha") {
                return AffyPlan(
                    intent = ActionTypes.GENERAL_CHAT,
                    speechResponse = "Ji boss.",
                    actions = emptyList()
                )
            }
        }

        return null
    }

    private fun matchNotificationCommand(lower: String, normalized: String): AffyPlan? {
        val contextManager = AffyConversationContextManager.getInstance()
        val pendingNotifications = contextManager.getPendingNotifications()

        // 0. User confirming reading ("haan suna do", "suna do", "parh do", "parh kar sunao", "haan parho", "sunao", "kya aaya")
        val isReadConfirmation = lower == "haan" || lower == "haan boss" || lower == "haan sunao" ||
                lower == "haan suna do" || lower == "suna do" || lower == "sunao" || lower == "parh do" ||
                lower == "parho" || lower == "parh kar sunao" || lower == "parh kar suna do" ||
                lower.contains("suna do") || lower.contains("parh do") || lower.contains("parh kar suna") ||
                lower.contains("kya aya") || lower.contains("kya message hai") || lower == "read" || lower == "yes"

        if (isReadConfirmation) {
            val targetMsg = pendingNotifications.firstOrNull()

            if (targetMsg != null) {
                contextManager.markNotificationRead(targetMsg.sender)
                val senderPrefix = if (targetMsg.sender.isNotBlank() && targetMsg.sender != "Kisi") "${targetMsg.sender}: " else ""
                val textToRead = "$senderPrefix'${targetMsg.messageText}'"
                return AffyPlan(
                    intent = ActionTypes.READ_NOTIFICATION,
                    speechResponse = textToRead,
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.READ_NOTIFICATION,
                            target = targetMsg.sender
                        )
                    )
                )
            }
        }

        // 0.5. Specific sender message query (e.g. "big star ka notification para hai, parh kar suna do", "big star ka message parho", "arham ka notification sunao")
        if (lower.contains("notification") || lower.contains("message") || lower.contains("para hai") || lower.contains("parh")) {
            for (msg in pendingNotifications) {
                val cleanSender = msg.sender.lowercase()
                if (cleanSender.isNotBlank() && lower.contains(cleanSender)) {
                    contextManager.markNotificationRead(msg.sender)
                    val senderPrefix = "${msg.sender}: "
                    val textToRead = "$senderPrefix'${msg.messageText}'"
                    return AffyPlan(
                        intent = ActionTypes.READ_NOTIFICATION,
                        speechResponse = textToRead,
                        actions = listOf(
                            AffyActionItem(
                                type = ActionTypes.READ_NOTIFICATION,
                                target = msg.sender
                            )
                        )
                    )
                }
            }
        }

        // 1. WhatsApp / Messages Inquiries
        if (lower.contains("whatsapp pe kisi ka message") || lower.contains("whatsapp par kisi ka message") ||
            lower.contains("kisi ka message aya hai") || lower.contains("koi message aya") ||
            lower.contains("whatsapp check karo") || lower.contains("whatsapp messages check") ||
            lower.contains("messages check karo") || lower.contains("kya message aya") ||
            lower.contains("unread messages") || lower.contains("messages parho") ||
            lower.contains("notification parho") || lower.contains("notifications sunao") ||
            lower.contains("notifications check") || lower.contains("messages sunao")
        ) {
            val filter = if (lower.contains("whatsapp")) "whatsapp" else "all"
            return AffyPlan(
                intent = ActionTypes.READ_NOTIFICATION,
                speechResponse = "Notifications check kar raha hoon boss.",
                actions = listOf(
                    AffyActionItem(
                        type = ActionTypes.READ_NOTIFICATION,
                        target = filter
                    )
                )
            )
        }

        // 2. Contextual handling of active notification ("iski chat handle karo", "iska reply karo", "iski chat kholo")
        if (lower.contains("iski chat") || lower.contains("iski chat handle") ||
            lower.contains("iska reply") || lower.contains("isko reply") ||
            lower.contains("inhe reply") || lower.contains("unhe reply") ||
            lower.contains("iski chat kholo")
        ) {
            val replyExtract = Regex("(?i)(?:reply|bolo|keh do|bhej do)\\s*(?:ke|that|:)?\\s*(.*)").find(normalized)
            val replyText = replyExtract?.groupValues?.get(1)?.trim() ?: ""

            return if (replyText.isNotBlank()) {
                AffyPlan(
                    intent = ActionTypes.SEND_WHATSAPP_MESSAGE,
                    speechResponse = "Ji boss, reply bhej raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.SEND_WHATSAPP_MESSAGE,
                            target = "iski",
                            text = replyText
                        )
                    )
                )
            } else {
                AffyPlan(
                    intent = ActionTypes.OPEN_APP,
                    speechResponse = "Ji boss, WhatsApp chat khol raha hoon.",
                    actions = listOf(
                        AffyActionItem(
                            type = ActionTypes.OPEN_APP,
                            target = "WhatsApp"
                        )
                    )
                )
            }
        }

        return null
    }

    private fun matchSingerCommand(lower: String, normalized: String): AffyPlan? {
        // Exclude third-party music app / video commands
        if (lower.contains("youtube") || lower.contains("spotify") || lower.contains("soundcloud") ||
            (lower.contains("chalao") && (lower.contains("video") || lower.contains("movie") || lower.contains("drama")))) {
            return null
        }

        val isSingingQuery = lower.contains("gana gao") || lower.contains("gaana gao") ||
                lower.contains("geet gao") || lower.contains("geet sunao") ||
                lower.contains("song gao") || lower.contains("song sunao") ||
                lower.contains("sing a song") || lower.contains("sing for me") ||
                lower.contains("sing me a song") || lower.contains("sing song") ||
                lower.contains("kuch gao") || lower.contains("kuch gaa ke") ||
                lower.contains("kuch gaa kar") || lower.contains("gaa ke sunao") ||
                lower.contains("ga kar sunao") || lower.contains("gana sunao") ||
                lower.contains("gaana sunao") || lower.contains("song banao") ||
                lower.contains("gana banao") || lower.contains("original song") ||
                (lower.contains("song") && (lower.contains("romantic") || lower.contains("sad") || lower.contains("happy") || lower.contains("urdu") || lower.contains("english") || lower.contains("hindi")))

        if (!isSingingQuery) return null

        return AffyPlan(
            intent = ActionTypes.SING_SONG,
            speechResponse = "Ji boss, main aapke liye gana ga raha hoon.",
            actions = listOf(
                AffyActionItem(
                    type = ActionTypes.SING_SONG,
                    text = normalized
                )
            )
        )
    }
}
