package com.example.ez

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.*

// Enhanced Application class
class EzApplication : Application() {
    companion object {
        const val NOTIFICATION_CHANNEL_ID = "app_scheduler_channel"
        const val NOTIFICATION_CHANNEL_NAME = "App Scheduler"
        const val SERVICE_CHANNEL_ID = "scheduler_service_channel"
        const val SERVICE_CHANNEL_NAME = "Scheduler Service"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("EzApplication", "Application created")
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = NotificationManagerCompat.from(this)

        // Channel for app launch notifications
        val launchChannel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        )
            .setName(NOTIFICATION_CHANNEL_NAME)
            .setDescription("Notifications for scheduled app launches")
            .build()

        // Channel for service notifications
        val serviceChannel = NotificationChannelCompat.Builder(
            SERVICE_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(SERVICE_CHANNEL_NAME)
            .setDescription("Background service notifications")
            .setShowBadge(false)
            .build()

        notificationManager.createNotificationChannel(launchChannel)
        notificationManager.createNotificationChannel(serviceChannel)
    }
}

// Enhanced Boot receiver
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Boot received: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "Device boot completed - restoring scheduled tasks")
                restoreScheduledTasks(context)
            }
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Handle HTC and other quick boot
                Log.d(TAG, "Quick boot detected - restoring scheduled tasks")
                restoreScheduledTasks(context)
            }
        }
    }

    private fun restoreScheduledTasks(context: Context) {
        try {
            val preferencesManager = SchedulePreferencesManager(context)
            val scheduledApps = preferencesManager.getScheduledApps()

            if (scheduledApps.isNotEmpty()) {
                Log.d(TAG, "Found ${scheduledApps.size} scheduled apps to restore")

                // Start the scheduler service
                val serviceIntent = Intent(context, SchedulerService::class.java).apply {
                    action = SchedulerService.ACTION_RESTORE_SCHEDULES
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "No scheduled apps to restore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore scheduled tasks", e)
        }
    }
}

// Enhanced Alarm receiver
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm received")

        // Acquire a wake lock to ensure the device stays awake
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmReceiver:WakeLock"
        )

        try {
            wakeLock.acquire(30_000L) // 30 seconds max

            val packageName = intent.getStringExtra(AppLaunchReceiver.EXTRA_PACKAGE_NAME)
            val appName = intent.getStringExtra(AppLaunchReceiver.EXTRA_APP_NAME)

            if (packageName != null) {
                Log.d(TAG, "Triggering scheduled launch for: $packageName")

                // Send broadcast to launch the app
                val launchIntent = Intent("com.example.ez.SCHEDULED_LAUNCH").apply {
                    putExtra(AppLaunchReceiver.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AppLaunchReceiver.EXTRA_APP_NAME, appName)
                }
                context.sendBroadcast(launchIntent)

                // Reschedule for next day
                rescheduleAlarm(context, packageName, appName, intent)
            }
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private fun rescheduleAlarm(context: Context, packageName: String, appName: String?, originalIntent: Intent) {
        try {
            val isRecurring = originalIntent.getBooleanExtra(SchedulerService.EXTRA_IS_RECURRING, false)

            if (!isRecurring) {
                Log.d(TAG, "Task is not recurring, not rescheduling: $packageName")
                return
            }

            // Get the original scheduling info from the intent
            val originalYear = originalIntent.getIntExtra(SchedulerService.EXTRA_YEAR, -1)
            val originalMonth = originalIntent.getIntExtra(SchedulerService.EXTRA_MONTH, -1)
            val originalDay = originalIntent.getIntExtra(SchedulerService.EXTRA_DAY_OF_MONTH, -1)
            val hour = originalIntent.getIntExtra(SchedulerService.EXTRA_HOUR, -1)
            val minute = originalIntent.getIntExtra(SchedulerService.EXTRA_MINUTE, -1)

            if (originalYear == -1 || originalMonth == -1 || originalDay == -1 || hour == -1 || minute == -1) {
                Log.e(TAG, "Invalid scheduling info in intent for $packageName")
                return
            }

            val nextCalendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, originalYear)
                set(Calendar.MONTH, originalMonth)
                set(Calendar.DAY_OF_MONTH, originalDay)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                // Schedule for next day
                add(Calendar.DAY_OF_MONTH, 1)
            }

            // Create new intent with updated date
            val newIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AppLaunchReceiver.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppLaunchReceiver.EXTRA_APP_NAME, appName)
                putExtra(SchedulerService.EXTRA_IS_RECURRING, true)
                putExtra(SchedulerService.EXTRA_YEAR, nextCalendar.get(Calendar.YEAR))
                putExtra(SchedulerService.EXTRA_MONTH, nextCalendar.get(Calendar.MONTH))
                putExtra(SchedulerService.EXTRA_DAY_OF_MONTH, nextCalendar.get(Calendar.DAY_OF_MONTH))
                putExtra(SchedulerService.EXTRA_HOUR, hour)
                putExtra(SchedulerService.EXTRA_MINUTE, minute)
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                packageName.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextCalendar.timeInMillis,
                        pendingIntent
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextCalendar.timeInMillis,
                        pendingIntent
                    )
                }
                else -> {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nextCalendar.timeInMillis,
                        pendingIntent
                    )
                }
            }

            // Update preferences with new date
            val preferencesManager = SchedulePreferencesManager(context)
            val scheduledApps = preferencesManager.getScheduledApps().toMutableList()
            val appIndex = scheduledApps.indexOfFirst { it.packageName == packageName }

            if (appIndex != -1) {
                val updatedApp = scheduledApps[appIndex].copy(
                    year = nextCalendar.get(Calendar.YEAR),
                    month = nextCalendar.get(Calendar.MONTH),
                    dayOfMonth = nextCalendar.get(Calendar.DAY_OF_MONTH)
                )
                scheduledApps[appIndex] = updatedApp
                preferencesManager.saveScheduledApps(scheduledApps)
            }

            Log.d(TAG, "Rescheduled recurring alarm for $packageName to ${nextCalendar.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarm for $packageName", e)
        }
    }
}

// Enhanced App Launch Receiver
class AppLaunchReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppLaunchReceiver"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_APP_NAME = "appName"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        when (intent.action) {
            "com.example.ez.LAUNCH_APP" -> {
                handleAppLaunch(context, intent)
            }
            "com.example.ez.SCHEDULED_LAUNCH" -> {
                handleScheduledLaunch(context, intent)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    private fun handleAppLaunch(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val appName = intent.getStringExtra(EXTRA_APP_NAME)

        if (packageName.isNullOrEmpty()) {
            Log.e(TAG, "Package name is null or empty")
            return
        }

        Log.d(TAG, "Handling app launch for: $packageName")

        val success = AppLauncherUtility.launchApp(context, packageName, appName)

        if (success) {
            Log.d(TAG, "Successfully launched app: $packageName")
        } else {
            Log.e(TAG, "Failed to launch app: $packageName")
        }
    }

    private fun handleScheduledLaunch(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val appName = intent.getStringExtra(EXTRA_APP_NAME)

        Log.d(TAG, "Handling scheduled launch for: $packageName")

        if (packageName.isNullOrEmpty()) {
            Log.e(TAG, "Package name is null or empty for scheduled launch")
            return
        }

        // Acquire wake lock for app launch
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AppLaunchReceiver:ScheduledLaunch"
        )

        try {
            wakeLock.acquire(15_000L) // 15 seconds

            // Use the utility to launch the app
            val success = AppLauncherUtility.launchApp(context, packageName, appName)

            if (success) {
                Log.d(TAG, "Successfully launched scheduled app: $packageName")
            } else {
                Log.e(TAG, "Failed to launch scheduled app: $packageName")
            }

        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}