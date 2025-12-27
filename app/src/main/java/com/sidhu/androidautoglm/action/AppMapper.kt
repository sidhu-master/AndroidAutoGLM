package com.sidhu.androidautoglm.action

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Data class representing installed application information
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val isEnabled: Boolean,
    val hasLauncher: Boolean
)

object AppMapper {
    private var predefinedAppMap: Map<String, String> = emptyMap()
    private var dynamicAppMap: Map<String, String> = emptyMap()
    private var isDynamicMappingAvailable = false  // True if dynamic apps were successfully loaded
    private const val CONFIG_FILE_NAME = "app_map.json"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadPredefinedMap()
    }

    private fun loadPredefinedMap() {
        val context = appContext ?: return
        val gson = Gson()
        val type = object : TypeToken<Map<String, String>>() {}.type

        try {
            context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { reader ->
                predefinedAppMap = gson.fromJson(reader, type) ?: emptyMap()
            }
        } catch (e: Exception) {
            Log.e("AppMapper", "Error loading app map from assets.", e)
            predefinedAppMap = emptyMap()
        }
    }

    /**
     * Get all installed applications from PackageManager
     */
    private fun getInstalledApps(): List<AppInfo> {
        val context = appContext ?: return emptyList()
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        return packages.mapNotNull { appInfo ->
            try {
                val packageName = appInfo.packageName
                val label = appInfo.loadLabel(pm).toString()
                val isEnabled = appInfo.enabled
                val hasLauncher = pm.getLaunchIntentForPackage(packageName) != null

                AppInfo(packageName, label, isEnabled, hasLauncher)
            } catch (e: Exception) {
                Log.w("AppMapper", "Error loading app info: ${appInfo.packageName}", e)
                null
            }
        }
    }

    /**
     * Normalize app name for consistent matching.
     * - Replace punctuation with spaces
     * - Trim leading/trailing whitespace
     * - Collapse multiple spaces to single space
     */
    private fun normalizeAppName(name: String): String {
        // Replace common punctuation with spaces
        val normalized = name.replace(Regex("[.,!?;:'\"\\(\\)\\[\\]\\{\\}<>\\\\/]"), " ")
        // Trim and collapse multiple spaces
        return normalized.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * Build app name to package name mapping from installed apps
     */
    private fun buildAppMapping(apps: List<AppInfo>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()

        apps.forEach { app ->
            // Skip disabled apps and apps without launch intent
            if (!app.isEnabled || !app.hasLauncher) return@forEach

            // Normalize app name and map to package name
            val normalizedName = normalizeAppName(app.label)
            mapping[normalizedName] = app.packageName
        }

        return mapping
    }

    /**
     * Refresh the dynamic app mapping from installed apps.
     * This method always refreshes - no cache.
     * Call on background thread (Dispatchers.IO) to avoid blocking.
     */
    fun refreshInstalledApps() {
        val apps = getInstalledApps()
        dynamicAppMap = buildAppMapping(apps)

        // Set flag: dynamic mapping is available only if we got some apps
        isDynamicMappingAvailable = dynamicAppMap.isNotEmpty()

        if (isDynamicMappingAvailable) {
            Log.i("AppMapper", "========== App Mapping Refreshed ==========")
            Log.i("AppMapper", "Total apps discovered: ${dynamicAppMap.size}")
            Log.i("AppMapper", "Using DYNAMIC mapping (predefined mapping disabled)")

            // Print complete app list sorted by app name
            dynamicAppMap.entries
                .sortedBy { it.key }
                .forEach { (appName, packageName) ->
                    Log.i("AppMapper", "  '$appName' → '$packageName'")
                }

            Log.i("AppMapper", "===========================================")
        } else {
            Log.w("AppMapper", "========== Using PREDEFINED Mapping ==========")
            Log.w("AppMapper", "No dynamic apps discovered (permission denied or empty)")
            Log.w("AppMapper", "Falling back to predefined app map (${predefinedAppMap.size} entries)")
            Log.w("AppMapper", "==============================================")
        }
    }

    /**
     * Find package name in the given app map using multi-strategy fuzzy matching.
     *
     * Matching order:
     * 1. Case-insensitive exact match
     * 2. Jaccard similarity (order-independent word matching)
     * 3. Levenshtein distance (typo tolerance)
     *
     * Split delimiters (normalized to spaces): space, dash (-), underscore (_), punctuation
     *
     * Jaccard algorithm:
     * - Split input into words (e.g., "Google Chrome" → ["google", "chrome"])
     * - Calculate J(A,B) = |A ∩ B| / |A ∪ B| for each candidate
     * - Select candidate with highest similarity (threshold: 0.5)
     *
     * Levenshtein algorithm (fallback):
     * - Calculate minimum edit operations (insert, delete, substitute)
     * - Select candidate with smallest distance (threshold: max 3 edits)
     *
     * Examples:
     * - Input: "Chrome" → exact match → "com.android.chrome"
     * - Input: "Center Game" → Jaccard with "Game Center" (0.67) → matches
     * - Input: "Chorme" → Levenshtein with "Chrome" (distance 2) → matches
     */
    private fun findPackageName(appMap: Map<String, String>, appName: String, mapSource: String): String? {
        // Normalize input app name (replace punctuation with spaces, trim, collapse spaces)
        val normalizedName = normalizeAppName(appName)

        // 1. Case-insensitive exact match (using normalized name)
        val exactMatch = appMap.entries.find { it.key.equals(normalizedName, ignoreCase = true) }
        if (exactMatch != null) {
            val matchType = if (exactMatch.key == normalizedName) "exact" else "case-insensitive"
            val normalizedLog = if (appName != normalizedName) " (normalized: '$normalizedName')" else ""
            Log.i("AppMapper", "✓ Found in $mapSource mapping ($matchType): '$appName'$normalizedLog → '${exactMatch.value}' (key: '${exactMatch.key}')")
            return exactMatch.value
        }

        // 2. Jaccard similarity (order-independent word matching)
        val inputWords = normalizedName.split(" ").map { it.lowercase() }.filter { it.isNotBlank() }

        if (inputWords.size > 1) {
            // Find all candidates with their Jaccard similarity
            val jaccardCandidates = appMap.entries.mapNotNull { entry ->
                val keyWords = entry.key.split(" ").map { it.lowercase() }
                val similarity = calculateJaccard(inputWords, keyWords)
                if (similarity >= 0.5) {  // Threshold: at least 50% overlap
                    MatchCandidate(entry, similarity)
                } else {
                    null
                }
            }

            // Select the candidate with the highest Jaccard similarity
            val bestJaccardMatch = jaccardCandidates.maxByOrNull { it.score }
            if (bestJaccardMatch != null) {
                val normalizedLog = if (appName != normalizedName) " (normalized: '$normalizedName')" else ""
                Log.i("AppMapper", "✓ Found in $mapSource mapping (Jaccard: ${String.format("%.2f", bestJaccardMatch.score)}): '$appName'$normalizedLog → '${bestJaccardMatch.entry.value}' (key: '${bestJaccardMatch.entry.key}')")
                return bestJaccardMatch.entry.value
            }
        }

        // 3. Levenshtein distance (fallback for typos)
        val levenshteinMatch = findWithLevenshtein(normalizedName, appMap)
        if (levenshteinMatch != null) {
            val normalizedLog = if (appName != normalizedName) " (normalized: '$normalizedName')" else ""
            Log.i("AppMapper", "✓ Found in $mapSource mapping (Levenshtein): '$appName'$normalizedLog → '${levenshteinMatch.value}' (key: '${levenshteinMatch.key}')")
            return levenshteinMatch.value
        }

        return null
    }

    /**
     * Calculate Jaccard similarity between two word lists.
     * J(A,B) = |A ∩ B| / |A ∪ B|
     *
     * Returns a value in [0, 1] where:
     * - 1.0 means identical word sets
     * - 0.0 means no common words
     *
     * Examples:
     * - ["game", "center"] vs ["game", "center"] → 1.0
     * - ["game", "center"] vs ["game", "center", "pro"] → 0.67
     * - ["game"] vs ["game", "center"] → 0.5
     */
    private fun calculateJaccard(inputWords: List<String>, keyWords: List<String>): Double {
        val inputSet = inputWords.toSet()
        val keySet = keyWords.toSet()

        val intersection = inputSet.intersect(keySet).size
        val union = inputSet.union(keySet).size

        return if (union > 0) {
            intersection.toDouble() / union
        } else {
            0.0
        }
    }

    /**
     * Find package name using Levenshtein distance (edit distance).
     * Returns the candidate with the smallest edit distance (threshold: max 3 edits).
     *
     * Levenshtein distance measures the minimum number of single-character edits
     * (insertions, deletions, or substitutions) required to change one string into another.
     *
     * Examples:
     * - "Chrome" vs "Chrome" → 0
     * - "Chrome" vs "Chorme" → 2 (swap r-o, then o-r)
     * - "Chrome" vs "Chrme" → 1 (delete o)
     */
    private fun findWithLevenshtein(normalizedName: String, appMap: Map<String, String>): Map.Entry<String, String>? {
        val threshold = 3  // Maximum allowed edit operations

        return appMap.entries
            .map { entry ->
                val distance = levenshtein(normalizedName.lowercase(), entry.key.lowercase())
                entry to distance
            }
            .filter { (_, distance) -> distance <= threshold }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    /**
     * Calculate Levenshtein distance between two strings.
     * Uses dynamic programming with O(m×n) time complexity.
     *
     * @param a First string
     * @param b Second string
     * @return Minimum edit distance (number of insertions, deletions, substitutions)
     */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length

        // Create a 2D array for dynamic programming
        val dp = Array(m + 1) { IntArray(n + 1) }

        // Initialize base cases
        for (i in 0..m) dp[i][0] = i  // Delete all characters from a
        for (j in 0..n) dp[0][j] = j  // Insert all characters into a

        // Fill the DP table
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,     // Deletion
                    dp[i][j - 1] + 1,     // Insertion
                    dp[i - 1][j - 1] + cost  // Substitution
                )
            }
        }

        return dp[m][n]
    }

    /**
     * Data class to hold match result with similarity score
     */
    private data class MatchCandidate(
        val entry: Map.Entry<String, String>,
        val score: Double  // Jaccard similarity (0-1) or other metric
    )

    /**
     * Get package name by app name.
     * Uses dynamic mapping if available, otherwise falls back to predefined mapping.
     */
    fun getPackageName(appName: String): String? {
        Log.d("AppMapper", "Looking up package name for: '$appName'")

        val appMap = if (isDynamicMappingAvailable) dynamicAppMap else predefinedAppMap
        val mapSource = if (isDynamicMappingAvailable) "DYNAMIC" else "PREDEFINED"

        val result = findPackageName(appMap, appName, mapSource)

        if (result == null) {
            Log.w("AppMapper", "✗ NOT FOUND in $mapSource mapping: '$appName'")
        }

        return result
    }
}
