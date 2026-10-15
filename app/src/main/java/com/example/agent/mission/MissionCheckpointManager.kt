package com.example.agent.mission

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.agent.AffyDiagnostics
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mission Checkpoint Manager (Phase 12 Core).
 * Persists safe, non-sensitive milestone checkpoints across Activity recreation, process lifecycle events,
 * and user pause/resume commands. Strictly blocks persistence of PINs, passwords, OTPs, or auth secrets.
 */
class MissionCheckpointManager(private val context: Context) {

    companion object {
        private const val TAG = "AffyCheckpoint"
        private const val PREFS_NAME = "affy_mission_checkpoints"
        private const val KEY_LATEST_CHECKPOINT = "latest_checkpoint"

        @Volatile
        private var memoryCachedCheckpoint: MissionCheckpoint? = null
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves a sanitized, non-sensitive mission checkpoint at verified milestones.
     */
    fun saveCheckpoint(mission: AffyMission, pauseReason: String? = null): MissionCheckpoint {
        val completedDescriptions = mission.completedGoals.map { it.description }
        val goalStatuses = mission.goals.associate { it.goalId to it.status }

        // Sanitize temporary context summary: exclude sensitive credentials
        val safeContext = mutableMapOf<String, String>()
        mission.temporaryMissionContext.selectedApp?.let { safeContext["app"] = it }
        mission.temporaryMissionContext.selectedPerson?.let { safeContext["person"] = it }
        mission.temporaryMissionContext.previousGoalResult?.let { safeContext["prev_result"] = it }

        val checkpoint = MissionCheckpoint(
            missionId = mission.missionId,
            originalUserRequest = mission.originalUserRequest,
            currentGoalIndex = mission.currentGoalIndex,
            goalStatuses = goalStatuses,
            completedGoalDescriptions = completedDescriptions,
            lastVerifiedApp = mission.temporaryMissionContext.selectedApp,
            lastVerifiedAction = mission.temporaryMissionContext.lastActionResult,
            safeContextSummary = safeContext,
            timestamp = System.currentTimeMillis(),
            pauseReason = pauseReason
        )

        memoryCachedCheckpoint = checkpoint

        try {
            val json = JSONObject().apply {
                put("missionId", checkpoint.missionId)
                put("originalUserRequest", checkpoint.originalUserRequest)
                put("currentGoalIndex", checkpoint.currentGoalIndex)
                put("timestamp", checkpoint.timestamp)
                put("pauseReason", checkpoint.pauseReason ?: "")
                put("lastVerifiedApp", checkpoint.lastVerifiedApp ?: "")
                put("lastVerifiedAction", checkpoint.lastVerifiedAction ?: "")
                put("completedDescriptions", JSONArray(checkpoint.completedGoalDescriptions))

                val statusObj = JSONObject()
                checkpoint.goalStatuses.forEach { (k, v) -> statusObj.put(k, v.name) }
                put("goalStatuses", statusObj)

                val ctxObj = JSONObject()
                checkpoint.safeContextSummary.forEach { (k, v) -> ctxObj.put(k, v) }
                put("safeContext", ctxObj)
            }
            prefs.edit().putString(KEY_LATEST_CHECKPOINT, json.toString()).apply()
            AffyDiagnostics.logTaskStage(
                stage = "CHECKPOINT_SAVED",
                taskId = mission.missionId,
                details = "Checkpoint saved at goal ${checkpoint.currentGoalIndex} (${completedDescriptions.size} completed)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving checkpoint: ${e.message}")
        }

        return checkpoint
    }

    /**
     * Retrieves the latest active checkpoint.
     */
    fun getLatestCheckpoint(): MissionCheckpoint? {
        memoryCachedCheckpoint?.let { return it }

        val raw = prefs.getString(KEY_LATEST_CHECKPOINT, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val missionId = obj.getString("missionId")
            val request = obj.getString("originalUserRequest")
            val goalIdx = obj.getInt("currentGoalIndex")
            val timestamp = obj.getLong("timestamp")
            val pauseReason = obj.optString("pauseReason").ifBlank { null }
            val lastApp = obj.optString("lastVerifiedApp").ifBlank { null }
            val lastAct = obj.optString("lastVerifiedAction").ifBlank { null }

            val completedList = mutableListOf<String>()
            val compArr = obj.optJSONArray("completedDescriptions")
            if (compArr != null) {
                for (i in 0 until compArr.length()) {
                    completedList.add(compArr.getString(i))
                }
            }

            val statusMap = mutableMapOf<String, GoalStatus>()
            val statusObj = obj.optJSONObject("goalStatuses")
            statusObj?.keys()?.forEach { k ->
                try {
                    statusMap[k] = GoalStatus.valueOf(statusObj.getString(k))
                } catch (_: Exception) {}
            }

            val ctxMap = mutableMapOf<String, String>()
            val ctxObj = obj.optJSONObject("safeContext")
            ctxObj?.keys()?.forEach { k -> ctxMap[k] = ctxObj.getString(k) }

            MissionCheckpoint(
                missionId = missionId,
                originalUserRequest = request,
                currentGoalIndex = goalIdx,
                goalStatuses = statusMap,
                completedGoalDescriptions = completedList,
                lastVerifiedApp = lastApp,
                lastVerifiedAction = lastAct,
                safeContextSummary = ctxMap,
                timestamp = timestamp,
                pauseReason = pauseReason
            ).also { memoryCachedCheckpoint = it }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring checkpoint: ${e.message}")
            null
        }
    }

    /**
     * Clears the saved checkpoint upon mission completion or cancellation.
     */
    fun clearCheckpoint() {
        memoryCachedCheckpoint = null
        prefs.edit().remove(KEY_LATEST_CHECKPOINT).apply()
        Log.d(TAG, "Checkpoint cleared")
    }
}
