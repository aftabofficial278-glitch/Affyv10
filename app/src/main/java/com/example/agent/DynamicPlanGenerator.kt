package com.example.agent

import com.example.data.model.ActionTypes
import com.example.data.model.AffyActionItem
import com.example.data.model.AgentTask
import com.example.data.model.AgentTaskStep
import java.util.UUID

class DynamicPlanGenerator {

    fun createPlanFromPrompt(prompt: String): AgentTask {
        val clean = prompt.trim()
        val lower = clean.lowercase()

        val steps = mutableListOf<AgentTaskStep>()

        when {
            // ChatGPT Image Generation
            lower.contains("chatgpt") && (lower.contains("image") || lower.contains("wallpaper") || lower.contains("photo") || lower.contains("picture")) -> {
                val imagePrompt = extractImagePrompt(clean)
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Ask ChatGPT to generate: $imagePrompt",
                        action = AffyActionItem(
                            type = ActionTypes.CHATGPT_GENERATE_IMAGE,
                            target = "com.openai.chatgpt",
                            text = imagePrompt
                        )
                    )
                )
            }

            // Follow-up: Download
            lower == "download it" || lower == "download" || lower == "save it" || lower == "save karo" -> {
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Download generated image from ChatGPT",
                        action = AffyActionItem(
                            type = ActionTypes.DOWNLOAD_CHATGPT_IMAGE,
                            target = "com.openai.chatgpt"
                        )
                    )
                )
            }

            // Follow-up: Set Wallpaper
            lower.contains("wallpaper") && (lower.contains("set") || lower.contains("apply") || lower.contains("laga")) -> {
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Apply verified image as device wallpaper",
                        action = AffyActionItem(
                            type = ActionTypes.SET_WALLPAPER
                        )
                    )
                )
            }

            // Calling intent
            lower.startsWith("call ") || lower.startsWith("phone ") || lower.contains("ko call karo") -> {
                val contactName = extractContactName(clean)
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Call $contactName",
                        action = AffyActionItem(
                            type = ActionTypes.PHONE_CALL,
                            target = contactName
                        )
                    )
                )
            }

            // Generic app opening / actions
            lower.startsWith("open ") -> {
                val appName = clean.substringAfter("open ").trim()
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Open $appName",
                        action = AffyActionItem(
                            type = ActionTypes.OPEN_APP,
                            target = appName
                        )
                    )
                )
            }

            else -> {
                steps.add(
                    AgentTaskStep(
                        stepId = UUID.randomUUID().toString(),
                        description = "Execute request: $clean",
                        action = AffyActionItem(
                            type = ActionTypes.TYPE_TEXT,
                            text = clean
                        )
                    )
                )
            }
        }

        return AgentTask(
            taskId = UUID.randomUUID().toString(),
            goal = clean,
            steps = steps
        )
    }

    private fun extractImagePrompt(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("generate ") -> input.substringAfter("generate ").trim()
            lower.contains("create ") -> input.substringAfter("create ").trim()
            lower.contains("bana do ") -> input.substringAfter("bana do ").trim()
            else -> input
        }
    }

    private fun extractContactName(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.startsWith("call ") -> input.substringAfter("call ").trim()
            lower.startsWith("phone ") -> input.substringAfter("phone ").trim()
            lower.contains(" ko call karo") -> input.substringBefore(" ko call karo").trim()
            else -> input
        }
    }
}
