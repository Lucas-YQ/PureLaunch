package com.example.ez

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SchedulePreferencesManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "app_scheduler_preferences"
        private const val KEY_SCHEDULED_APPS = "scheduled_apps"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val TAG = "SchedulePreferencesManager"
    }

    private val preferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveScheduledApps(apps: List<SchedulerService.ScheduledApp>) {
        try {
            val jsonArray = JSONArray()
            apps.forEach { app ->
                val jsonObject = JSONObject().apply {
                    put("appName", app.appName)
                    put("packageName", app.packageName)
                    put("hour", app.hour)
                    put("minute", app.minute)
                    put("year", app.year)
                    put("month", app.month)
                    put("dayOfMonth", app.dayOfMonth)
                    put("isRecurring", app.isRecurring)
                    put("lastTriggered", app.lastTriggered)
                    put("isEnabled", app.isEnabled)
                }
                jsonArray.put(jsonObject)
            }

            preferences.edit()
                .putString(KEY_SCHEDULED_APPS, jsonArray.toString())
                .apply()

            Log.d(TAG, "Saved ${apps.size} scheduled apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scheduled apps", e)
        }
    }

    fun getScheduledApps(): List<SchedulerService.ScheduledApp> {
        return try {
            val jsonString = preferences.getString(KEY_SCHEDULED_APPS, null)
            if (jsonString == null) {
                Log.d(TAG, "No scheduled apps found")
                return emptyList()
            }

            val jsonArray = JSONArray(jsonString)
            val apps = mutableListOf<SchedulerService.ScheduledApp>()

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                val app = SchedulerService.ScheduledApp(
                    appName = jsonObject.getString("appName"),
                    packageName = jsonObject.getString("packageName"),
                    hour = jsonObject.getInt("hour"),
                    minute = jsonObject.getInt("minute"),
                    year = jsonObject.getInt("year"),
                    month = jsonObject.getInt("month"),
                    dayOfMonth = jsonObject.getInt("dayOfMonth"),
                    isRecurring = jsonObject.optBoolean("isRecurring", false),
                    lastTriggered = jsonObject.optLong("lastTriggered", 0L),
                    isEnabled = jsonObject.optBoolean("isEnabled", true)
                )
                apps.add(app)
            }

            Log.d(TAG, "Loaded ${apps.size} scheduled apps")
            apps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scheduled apps", e)
            emptyList()
        }
    }

    fun clearScheduledApps() {
        preferences.edit()
            .remove(KEY_SCHEDULED_APPS)
            .apply()
        Log.d(TAG, "Cleared all scheduled apps")
    }

    fun setServiceEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_SERVICE_ENABLED, enabled)
            .apply()
    }

    fun isServiceEnabled(): Boolean {
        return preferences.getBoolean(KEY_SERVICE_ENABLED, true)
    }

    fun removeScheduledApp(packageName: String) {
        try {
            val currentApps = getScheduledApps().filter { it.packageName != packageName }
            saveScheduledApps(currentApps)
            Log.d(TAG, "Removed scheduled app: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove scheduled app: $packageName", e)
        }
    }

    fun updateScheduledApp(app: SchedulerService.ScheduledApp) {
        try {
            val currentApps = getScheduledApps().toMutableList()
            val index = currentApps.indexOfFirst { it.packageName == app.packageName }

            if (index != -1) {
                currentApps[index] = app
            } else {
                currentApps.add(app)
            }

            saveScheduledApps(currentApps)
            Log.d(TAG, "Updated scheduled app: ${app.appName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update scheduled app: ${app.appName}", e)
        }
    }
}