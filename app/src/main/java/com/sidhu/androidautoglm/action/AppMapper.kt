package com.sidhu.androidautoglm.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log

/**
 * Discovers and maps launcher applications to their package names.
 * Implements [AppMatcher.DataSource] to provide app mapping data.
 */
object AppMapper : AppMatcher.DataSource {
    override val map: Map<String, String>
        get() = appMap

    private var appMap: Map<String, String> = emptyMap()
    private var appContext: Context? = null

    /**
     * Initialize AppMapper with application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.i("AppMapper", "Initialized")
    }

    /**
     * Get all launcher applications using queryIntentActivities.
     * This method does not require QUERY_ALL_PACKAGES permission and automatically
     * filters out disabled apps and apps without a launcher intent.
     *
     * @return List of pairs (packageName, label)
     */
    private fun getLauncherApps(): List<Pair<String, String>> {
        val context = appContext ?: return emptyList()
        val pm = context.packageManager

        // Create intent for CATEGORY_LAUNCHER
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        // Query all apps that can handle the launcher intent
        val resolveInfos: List<ResolveInfo>
        try {
            resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            Log.e("AppMapper", "Error querying launcher apps", e)
            return emptyList()
        }

        return resolveInfos.mapNotNull { resolveInfo ->
            try {
                val activityInfo = resolveInfo.activityInfo
                val packageName = activityInfo.packageName
                val label = resolveInfo.loadLabel(pm).toString()

                // Apps returned by queryIntentActivities are:
                // - Automatically enabled (disabled apps are not returned)
                // - Have a launcher intent (that's how they were found)
                packageName to label
            } catch (e: Exception) {
                Log.w("AppMapper", "Error loading app info: ${resolveInfo.activityInfo.packageName}", e)
                null
            }
        }
    }

    /**
     * Build app name to package name mapping from launcher apps.
     *
     * @param apps List of pairs (packageName, label)
     * @return Map of normalized app names to package names
     */
    private fun buildAppMapping(apps: List<Pair<String, String>>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()

        apps.forEach { (packageName, label) ->
            // Normalize app name and map to package name
            val normalizedName = AppMatcher.normalizeName(label)
            mapping[normalizedName] = packageName
        }

        return mapping
    }

    /**
     * 获取本机已安装应用名称列表，供主模型分析任务时选择目标应用。
     * 返回格式：逗号分隔的应用名，如 "美团, 饿了么, 微信, 支付宝, ..."
     * 最多返回 150 个，避免 prompt 过长。
     */
    fun getInstalledAppNamesForPrompt(): String {
        return appMap.keys
            .sorted()
            .take(150)
            .joinToString("、")
    }

    /**
     * Refresh the launcher app mapping.
     * This method rebuilds the entire mapping from scratch - no cache.
     * Call on background thread (Dispatchers.IO) to avoid blocking.
     */
    fun refreshLauncherApps() {
        val apps = getLauncherApps()
        appMap = buildAppMapping(apps)

        if (appMap.isNotEmpty()) {
            Log.i("AppMapper", "========== App Mapping Refreshed ==========")
            Log.i("AppMapper", "Total launcher apps discovered: ${appMap.size}")

            // Print complete app list sorted by app name
            appMap.entries
                .sortedBy { it.key }
                .forEach { (appName, packageName) ->
                    Log.i("AppMapper", "  '$appName' → '$packageName'")
                }

            Log.i("AppMapper", "===========================================")
        } else {
            Log.w("AppMapper", "========== No Apps Discovered ==========")
            Log.w("AppMapper", "No launcher apps found. Check <queries> declaration in manifest.")
            Log.w("AppMapper", "========================================")
        }
    }
}
