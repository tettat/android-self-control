package com.control.app.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents learned operational knowledge for a specific app.
 */
data class AppSkill(
    val packageName: String,
    val appName: String,
    val tips: MutableList<String>,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Persists per-app operational skills (tips/SOPs) to SharedPreferences.
 * Skills are JSON-serialized with key format: "skill_{packageName}".
 */
class SkillStore(context: Context) {

    companion object {
        private const val TAG = "SkillStore"
        private const val PREFS_NAME = "app_skills"
        private const val KEY_PREFIX = "skill_"
        private const val MAX_TIPS_PER_APP = 20
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSkill(packageName: String): AppSkill? {
        val json = prefs.getString(KEY_PREFIX + packageName, null) ?: return null
        return try {
            deserializeSkill(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize skill for $packageName: ${e.message}")
            null
        }
    }

    fun saveSkill(skill: AppSkill) {
        val json = serializeSkill(skill)
        prefs.edit().putString(KEY_PREFIX + skill.packageName, json).apply()
    }

    /**
     * Add a tip to an existing skill or create a new one.
     * Enforces [MAX_TIPS_PER_APP] limit by removing the oldest tip when exceeded.
     */
    fun addTip(packageName: String, appName: String, tip: String) {
        val existing = getSkill(packageName)
        val skill = if (existing != null) {
            existing.copy(
                appName = appName,
                tips = existing.tips,
                lastUpdated = System.currentTimeMillis()
            )
        } else {
            AppSkill(
                packageName = packageName,
                appName = appName,
                tips = mutableListOf()
            )
        }

        // Avoid duplicate tips
        if (skill.tips.any { it == tip }) {
            Log.d(TAG, "Duplicate tip skipped for $packageName: $tip")
            return
        }

        skill.tips.add(tip)

        // Enforce max tips limit
        while (skill.tips.size > MAX_TIPS_PER_APP) {
            skill.tips.removeAt(0)
        }

        saveSkill(skill)
        Log.d(TAG, "Saved tip for $packageName (${skill.tips.size} tips total)")
    }

    fun getAllSkills(): List<AppSkill> {
        return prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) }
            .mapNotNull { entry ->
                try {
                    deserializeSkill(entry.value as String)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to deserialize skill ${entry.key}: ${e.message}")
                    null
                }
            }
            .sortedByDescending { it.lastUpdated }
    }

    fun deleteSkill(packageName: String) {
        prefs.edit().remove(KEY_PREFIX + packageName).apply()
    }

    fun clearAllSkills() {
        prefs.edit().clear().apply()
    }

    private fun serializeSkill(skill: AppSkill): String {
        val obj = JSONObject().apply {
            put("packageName", skill.packageName)
            put("appName", skill.appName)
            put("lastUpdated", skill.lastUpdated)
            put("tips", JSONArray().apply {
                skill.tips.forEach { put(it) }
            })
        }
        return obj.toString()
    }

    private fun deserializeSkill(json: String): AppSkill {
        val obj = JSONObject(json)
        val tipsArray = obj.getJSONArray("tips")
        val tips = mutableListOf<String>()
        for (i in 0 until tipsArray.length()) {
            tips.add(tipsArray.getString(i))
        }
        return AppSkill(
            packageName = obj.getString("packageName"),
            appName = obj.getString("appName"),
            tips = tips,
            lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
        )
    }
}
