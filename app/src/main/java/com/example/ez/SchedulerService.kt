package com.example.ez

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class SchedulerService : Service() {
    companion object {
        private const val TAG = "SchedulerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "scheduler_service_channel"
        private const val CHANNEL_NAME = "App Scheduler Service"
        private const val WAKELOCK_TAG = "SchedulerService:WakeLock"

        const val ACTION_START_MONITORING = "start_monitoring"
        const val ACTION_STOP_MONITORING = "stop_monitoring"
        const val ACTION_UPDATE_SCHEDULE = "update_schedule"
        const val ACTION_REMOVE_SCHEDULE = "remove_schedule"
        const val ACTION_RESTORE_SCHEDULES = "restore_schedules"

        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val EXTRA_YEAR = "year"
        const val EXTRA_MONTH = "month"
        const val EXTRA_DAY_OF_MONTH = "day_of_month"
        const val EXTRA_IS_RECURRING = "is_recurring"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var monitoringJob: Job? = null
    private val scheduledApps = ConcurrentHashMap<String, ScheduledApp>()
    private lateinit var alarmManager: AlarmManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var preferencesManager: SchedulePreferencesManager

    data class ScheduledApp(
        val appName: String,
        val packageName: String,
        val hour: Int,
        val minute: Int,
        val year: Int,
        val month: Int,
        val dayOfMonth: Int,
        val isRecurring: Boolean = false,
        var lastTriggered: Long = 0L,
        var isEnabled: Boolean = true
    )

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SchedulerService created")

        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        preferencesManager = SchedulePreferencesManager(this)

        createNotificationChannel()
        acquireWakeLock()

        // Restore schedules from preferences
        restoreScheduledApps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SchedulerService onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_MONITORING -> {
                startForegroundService()
                startMonitoring()
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
            ACTION_UPDATE_SCHEDULE -> {
                updateSchedule(intent)
            }
            ACTION_REMOVE_SCHEDULE -> {
                removeSchedule(intent)
            }
            ACTION_RESTORE_SCHEDULES -> {
                restoreScheduledApps()
            }
            else -> {
                // Handle app restart or system restart
                if (scheduledApps.isNotEmpty()) {
                    startForegroundService()
                    startMonitoring()
                }
            }
        }

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SchedulerService destroyed")

        stopMonitoring()
        releaseWakeLock()
        serviceScope.cancel()

        // Save current schedules before destruction
        saveScheduledApps()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - keeping service alive")

        // Don't stop the service when task is removed
        // This ensures background operation continues
        if (scheduledApps.isNotEmpty()) {
            startForegroundService()
            startMonitoring()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for app scheduling"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeCount = scheduledApps.values.count { it.isEnabled }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Scheduler")
            .setContentText("$activeCount scheduled app(s) - Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            ).apply {
                acquire(10 * 60 * 1000L) // 10 minutes
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock", e)
        }
    }

    private fun startMonitoring() {
        Log.d(TAG, "Starting background monitoring")

        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkScheduledApps()

                    // Check every 30 seconds
                    delay(30_000)

                    // Renew wake lock every 5 minutes
                    if (System.currentTimeMillis() % 300_000 < 30_000) {
                        renewWakeLock()
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in monitoring loop", e)
                    delay(60_000) // Wait longer on error
                }
            }
        }
    }

    private fun stopMonitoring() {
        Log.d(TAG, "Stopping background monitoring")
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private fun renewWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.acquire(10 * 60 * 1000L) // Renew for 10 minutes
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to renew WakeLock", e)
        }
    }

    private fun updateSchedule(intent: Intent) {
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        val year = intent.getIntExtra(EXTRA_YEAR, -1)
        val month = intent.getIntExtra(EXTRA_MONTH, -1)
        val dayOfMonth = intent.getIntExtra(EXTRA_DAY_OF_MONTH, -1)
        val isRecurring = intent.getBooleanExtra(EXTRA_IS_RECURRING, false)

        if (hour == -1 || minute == -1 || year == -1 || month == -1 || dayOfMonth == -1) {
            Log.w(TAG, "Invalid date/time provided for schedule update")
            return
        }

        val scheduledApp = ScheduledApp(appName, packageName, hour, minute, year, month, dayOfMonth, isRecurring)
        scheduledApps[packageName] = scheduledApp

        // Set up AlarmManager for precise timing
        scheduleAlarm(scheduledApp)

        // Save to preferences
        saveScheduledApps()

        // Update notification
        updateNotification()

        val dateStr = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
        val timeStr = String.format("%02d:%02d", hour, minute)
        Log.d(TAG, "Updated schedule for: $appName at $dateStr $timeStr (recurring: $isRecurring)")
    }

    private fun removeSchedule(intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return

        scheduledApps.remove(packageName)
        cancelAlarm(packageName)
        saveScheduledApps()
        updateNotification()

        Log.d(TAG, "Removed schedule for: $packageName")

        // Stop service if no more schedules
        if (scheduledApps.isEmpty()) {
            stopSelf()
        }
    }

    private fun scheduleAlarm(scheduledApp: ScheduledApp) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, scheduledApp.year)
            set(Calendar.MONTH, scheduledApp.month)
            set(Calendar.DAY_OF_MONTH, scheduledApp.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, scheduledApp.hour)
            set(Calendar.MINUTE, scheduledApp.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Check if the scheduled time is in the past
        val now = Calendar.getInstance()
        if (calendar.before(now)) {
            if (scheduledApp.isRecurring) {
                // For recurring tasks, schedule for the next occurrence
                when {
                    scheduledApp.dayOfMonth != -1 -> {
                        // Monthly recurring - next month
                        calendar.add(Calendar.MONTH, 1)
                    }
                    else -> {
                        // Daily recurring - next day
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                    }
                }
            } else {
                Log.w(TAG, "Scheduled time is in the past and not recurring: ${scheduledApp.appName}")
                return
            }
        }

        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra(AppLaunchReceiver.EXTRA_PACKAGE_NAME, scheduledApp.packageName)
            putExtra(AppLaunchReceiver.EXTRA_APP_NAME, scheduledApp.appName)
            putExtra(EXTRA_IS_RECURRING, scheduledApp.isRecurring)
            putExtra(EXTRA_YEAR, scheduledApp.year)
            putExtra(EXTRA_MONTH, scheduledApp.month)
            putExtra(EXTRA_DAY_OF_MONTH, scheduledApp.dayOfMonth)
            putExtra(EXTRA_HOUR, scheduledApp.hour)
            putExtra(EXTRA_MINUTE, scheduledApp.minute)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            scheduledApp.packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            }
            Log.d(TAG, "Alarm scheduled for ${scheduledApp.appName} at ${calendar.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alarm for ${scheduledApp.appName}", e)
        }
    }

    private fun cancelAlarm(packageName: String) {
        val intent = Intent(this, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun checkScheduledApps() {
        val currentTime = Calendar.getInstance()
        val currentHour = currentTime.get(Calendar.HOUR_OF_DAY)
        val currentMinute = currentTime.get(Calendar.MINUTE)
        val currentTimeMillis = System.currentTimeMillis()

        scheduledApps.values.forEach { scheduledApp ->
            if (scheduledApp.isEnabled &&
                scheduledApp.hour == currentHour &&
                scheduledApp.minute == currentMinute &&
                (currentTimeMillis - scheduledApp.lastTriggered) > 60_000) { // Prevent multiple triggers

                launchScheduledApp(scheduledApp)
                scheduledApp.lastTriggered = currentTimeMillis
            }
        }
    }

    private fun launchScheduledApp(scheduledApp: ScheduledApp) {
        Log.d(TAG, "Launching scheduled app: ${scheduledApp.appName}")

        val success = AppLauncherUtility.launchApp(
            applicationContext,
            scheduledApp.packageName,
            scheduledApp.appName
        )

        if (success) {
            Log.d(TAG, "Successfully launched scheduled app: ${scheduledApp.appName}")
            showLaunchNotification(scheduledApp)
        } else {
            Log.e(TAG, "Failed to launch scheduled app: ${scheduledApp.appName}")
        }
    }

    private fun showLaunchNotification(scheduledApp: ScheduledApp) {
        val notification = NotificationCompat.Builder(this, EzApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("App Launched")
            .setContentText("${scheduledApp.appName} has been launched")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        notificationManager?.notify(scheduledApp.packageName.hashCode(), notification)
    }

    private fun updateNotification() {
        val notification = createServiceNotification()
        val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun saveScheduledApps() {
        preferencesManager.saveScheduledApps(scheduledApps.values.toList())
    }

    private fun restoreScheduledApps() {
        val savedApps = preferencesManager.getScheduledApps()
        scheduledApps.clear()
        savedApps.forEach { app ->
            scheduledApps[app.packageName] = app
            scheduleAlarm(app)
        }
        Log.d(TAG, "Restored ${savedApps.size} scheduled apps")
    }
}