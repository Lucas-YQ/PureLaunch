package com.example.ez

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast

object AppLauncherUtility {
    private const val TAG = "AppLauncherUtility"

    fun launchApp(context: Context, packageName: String, appName: String? = null): Boolean {
        Log.d(TAG, "Attempting to launch app: $packageName")

        return try {
            // Method 1: Standard getLaunchIntentForPackage
            val packageManager = context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                )

                context.startActivity(launchIntent)
                Log.d(TAG, "Successfully launched app using method 1: $packageName")
                showSuccessToast(context, appName)
                return true
            }

            // Method 2: Query activities and launch main activity
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }

            val activities = packageManager.queryIntentActivities(mainIntent, 0)
            if (activities.isNotEmpty()) {
                val activityInfo = activities[0].activityInfo
                val componentName = ComponentName(
                    activityInfo.packageName,
                    activityInfo.name
                )

                val finalIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = componentName
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }

                context.startActivity(finalIntent)
                Log.d(TAG, "Successfully launched app using method 2: $packageName")
                showSuccessToast(context, appName)
                return true
            }

            // Method 3: Try to get all activities and find launchable one
            val allActivities = try {
                packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Package not found: $packageName", e)
                return false
            }

            allActivities.activities?.let { activities ->
                for (activity in activities) {
                    if (activity.exported) {
                        val componentIntent = Intent().apply {
                            component = ComponentName(packageName, activity.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }

                        try {
                            context.startActivity(componentIntent)
                            Log.d(TAG, "Successfully launched app using method 3: $packageName")
                            showSuccessToast(context, appName)
                            return true
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to launch activity: ${activity.name}", e)
                        }
                    }
                }
            }

            Log.e(TAG, "All launch methods failed for: $packageName")
            showErrorToast(context, appName)
            false

        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $packageName", e)
            showErrorToast(context, appName)
            false
        }
    }

    private fun showSuccessToast(context: Context, appName: String?) {
        try {
            Toast.makeText(
                context,
                "Launched ${appName ?: "app"}",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not show success toast", e)
        }
    }

    private fun showErrorToast(context: Context, appName: String?) {
        try {
            Toast.makeText(
                context,
                "Failed to launch ${appName ?: "app"}",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "Could not show error toast", e)
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getAppName(context: Context, packageName: String): String? {
        return try {
            val packageManager = context.packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            applicationInfo.loadLabel(packageManager).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for: $packageName", e)
            null
        }
    }
}