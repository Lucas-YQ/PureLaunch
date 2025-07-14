package com.example.ez

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// Updated Data Classes
data class AppInfo(
    val appName: String,
    val packageName: String,
    val iconBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppInfo

        if (appName != other.appName) return false
        if (packageName != other.packageName) return false
        if (!iconBytes.contentEquals(other.iconBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = appName.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + iconBytes.contentHashCode()
        return result
    }
}

data class ScheduledTask(
    val appInfo: AppInfo,
    val hour: Int,
    val minute: Int,
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
    val isRecurring: Boolean = false,
    val isActive: Boolean = true
)

data class AppSchedulerUiState(
    val installedApps: List<AppInfo> = emptyList(),
    val selectedApp: AppInfo? = null,
    val selectedHour: Int = -1,
    val selectedMinute: Int = -1,
    val selectedYear: Int = -1,
    val selectedMonth: Int = -1,
    val selectedDayOfMonth: Int = -1,
    val isRecurring: Boolean = false,
    val showAppPicker: Boolean = false,
    val showDatePicker: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val scheduledTasks: List<ScheduledTask> = emptyList(),
    val isServiceRunning: Boolean = false
)

// Enhanced ViewModel with Service Integration
class AppSchedulerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AppSchedulerUiState())
    val uiState: StateFlow<AppSchedulerUiState> = _uiState.asStateFlow()

    fun loadInstalledApps(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val apps = getInstalledApps(context)
                _uiState.value = _uiState.value.copy(
                    installedApps = apps,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load apps: ${e.message}"
                )
            }
        }
    }

    fun selectApp(app: AppInfo) {
        _uiState.value = _uiState.value.copy(selectedApp = app)
    }

    fun setTime(hour: Int, minute: Int) {
        _uiState.value = _uiState.value.copy(
            selectedHour = hour,
            selectedMinute = minute
        )
    }

    fun setDate(year: Int, month: Int, dayOfMonth: Int) {
        _uiState.value = _uiState.value.copy(
            selectedYear = year,
            selectedMonth = month,
            selectedDayOfMonth = dayOfMonth
        )
    }

    fun setRecurring(recurring: Boolean) {
        _uiState.value = _uiState.value.copy(isRecurring = recurring)
    }

    fun showAppPicker(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAppPicker = show)
    }

    fun showDatePicker(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDatePicker = show)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun scheduleApp(context: Context) {
        val currentState = _uiState.value
        val selectedApp = currentState.selectedApp
        val hour = currentState.selectedHour
        val minute = currentState.selectedMinute
        val year = currentState.selectedYear
        val month = currentState.selectedMonth
        val dayOfMonth = currentState.selectedDayOfMonth
        val isRecurring = currentState.isRecurring

        if (selectedApp == null || hour == -1 || minute == -1 ||
            year == -1 || month == -1 || dayOfMonth == -1) {
            _uiState.value = currentState.copy(
                errorMessage = "Please select app, date, and time"
            )
            return
        }

        viewModelScope.launch {
            try {
                // Start the scheduler service
                startSchedulerService(context)

                // Update the service with new schedule
                updateSchedulerService(context, selectedApp, hour, minute, year, month, dayOfMonth, isRecurring)

                // Add to local scheduled tasks
                val newTask = ScheduledTask(selectedApp, hour, minute, year, month, dayOfMonth, isRecurring)
                val updatedTasks = currentState.scheduledTasks + newTask

                _uiState.value = currentState.copy(
                    scheduledTasks = updatedTasks,
                    isServiceRunning = true
                )

                // Show success message
                withContext(Dispatchers.Main) {
                    val dateStr = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
                    val timeStr = String.format("%02d:%02d", hour, minute)
                    val recurringStr = if (isRecurring) " (Recurring)" else ""
                    Toast.makeText(
                        context,
                        "Scheduled ${selectedApp.appName} for $dateStr at $timeStr$recurringStr",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                _uiState.value = currentState.copy(
                    errorMessage = "Failed to schedule app: ${e.message}"
                )
            }
        }
    }

    fun removeScheduledTask(context: Context, task: ScheduledTask) {
        viewModelScope.launch {
            try {
                // Remove from service
                removeFromSchedulerService(context, task.appInfo)

                // Update local state
                val updatedTasks = _uiState.value.scheduledTasks.filter { it != task }
                _uiState.value = _uiState.value.copy(scheduledTasks = updatedTasks)

                // Stop service if no more tasks
                if (updatedTasks.isEmpty()) {
                    stopSchedulerService(context)
                    _uiState.value = _uiState.value.copy(isServiceRunning = false)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Removed schedule for ${task.appInfo.appName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to remove schedule: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startSchedulerService(context: Context) {
        val intent = Intent(context, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_START_MONITORING
        }
        context.startForegroundService(intent)
    }

    private fun stopSchedulerService(context: Context) {
        val intent = Intent(context, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_STOP_MONITORING
        }
        context.startService(intent)
    }

    private fun updateSchedulerService(context: Context, appInfo: AppInfo, hour: Int, minute: Int, year: Int, month: Int, dayOfMonth: Int, isRecurring: Boolean) {
        val intent = Intent(context, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_UPDATE_SCHEDULE
            putExtra(SchedulerService.EXTRA_APP_NAME, appInfo.appName)
            putExtra(SchedulerService.EXTRA_PACKAGE_NAME, appInfo.packageName)
            putExtra(SchedulerService.EXTRA_HOUR, hour)
            putExtra(SchedulerService.EXTRA_MINUTE, minute)
            putExtra(SchedulerService.EXTRA_YEAR, year)
            putExtra(SchedulerService.EXTRA_MONTH, month)
            putExtra(SchedulerService.EXTRA_DAY_OF_MONTH, dayOfMonth)
            putExtra(SchedulerService.EXTRA_IS_RECURRING, isRecurring)
        }
        context.startService(intent)
    }

    private fun removeFromSchedulerService(context: Context, appInfo: AppInfo) {
        val intent = Intent(context, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_UPDATE_SCHEDULE
            putExtra(SchedulerService.EXTRA_APP_NAME, appInfo.appName)
            putExtra(SchedulerService.EXTRA_PACKAGE_NAME, appInfo.packageName)
            putExtra(SchedulerService.EXTRA_HOUR, -1) // -1 indicates removal
            putExtra(SchedulerService.EXTRA_MINUTE, -1)
        }
        context.startService(intent)
    }

    private suspend fun getInstalledApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { appInfo ->
                    appInfo.packageName != context.packageName &&
                            hasLauncherActivity(packageManager, appInfo.packageName)
                }
                .mapNotNull { appInfo ->
                    try {
                        val appName = appInfo.loadLabel(packageManager).toString()
                        val iconBytes = loadAppIcon(packageManager, appInfo)
                        AppInfo(appName, appInfo.packageName, iconBytes)
                    } catch (e: Exception) {
                        null
                    }
                }
                .sortedBy { it.appName.lowercase() }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun hasLauncherActivity(pm: PackageManager, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val activities = pm.queryIntentActivities(intent, 0)
        return activities.isNotEmpty()
    }

    private fun loadAppIcon(pm: PackageManager, appInfo: ApplicationInfo): ByteArray {
        return try {
            val drawable = appInfo.loadIcon(pm)
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            } else ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}