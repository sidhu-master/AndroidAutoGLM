package com.sidhu.androidautoglm.action

import android.util.Log

/**
 * Fuzzy string matching utility for finding values by keys.
 * Supports exact matching, Jaccard similarity, and Levenshtein distance.
 *
 * This is the main entry point for app name to package name matching.
 * It uses a [DataSource] to obtain the mapping data.
 */
object AppMatcher {

    /**
     * Data source interface for providing key-value mappings.
     * Implementations (e.g., [AppMapper]) provide the actual data.
     */
    interface DataSource {
        /** The current mapping of normalized keys to values */
        val map: Map<String, String>
    }

    private var dataSource: DataSource? = null

    /**
     * Initialize AppMatcher with a data source.
     * Must be called before [getPackageName].
     *
     * @param source The data source (typically AppMapper)
     */
    fun init(source: DataSource) {
        dataSource = source
        Log.i("AppMatcher", "Initialized with data source: ${source::class.simpleName}")
    }

    /**
     * Get package name by app name using fuzzy matching with lazy refresh.
     *
     * Strategy:
     * 1. Try to find in current cache (fast path)
     * 2. If not found, refresh the cache from data source
     * 3. Try again with refreshed data
     *
     * This ensures zero overhead for cache hits while automatically
     * detecting newly installed apps.
     *
     * @param appName The app name to search for (e.g., "微信", "Chrome")
     * @return The corresponding package name, or null if not found
     */
    fun getPackageName(appName: String): String? {
        Log.d("AppMatcher", "Looking up package name for: '$appName'")

        val source = dataSource
        if (source == null) {
            Log.e("AppMatcher", "DataSource not initialized. Call init() first.")
            return null
        }

        // First attempt: find in current cache
        var result = findValue(source.map, appName)
        if (result != null) {
            Log.d("AppMatcher", "✓ Found (cached): '$appName' → '$result'")
            return result
        }

        // Not found in cache - refresh and try again
        Log.d("AppMatcher", "Not found in cache, refreshing data source...")
        (source as? AppMapper)?.refreshLauncherApps()

        // Second attempt: find with refreshed data
        result = findValue(source.map, appName)
        if (result != null) {
            Log.i("AppMatcher", "✓ Found (after refresh): '$appName' → '$result'")
        } else {
            Log.w("AppMatcher", "✗ NOT FOUND: '$appName'")
        }

        return result
    }

    /**
     * Find a value in the given map by key using multi-strategy fuzzy matching.
     * Internal implementation - use [getPackageName] for external access.
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
     *
     * @param map Map of normalized keys to values
     * @param key The key to search for
     * @return The corresponding value, or null if no match found
     */
    private fun findValue(map: Map<String, String>, key: String): String? {
        // Normalize input key (replace punctuation with spaces, trim, collapse spaces)
        val normalizedKey = normalizeName(key)

        // 1. Case-insensitive exact match (using normalized key)
        val exactMatch = map.entries.find { it.key.equals(normalizedKey, ignoreCase = true) }
        if (exactMatch != null) {
            val matchType = if (exactMatch.key == normalizedKey) "exact" else "case-insensitive"
            val normalizedLog = if (key != normalizedKey) " (normalized: '$normalizedKey')" else ""
            Log.d("AppMatcher", "  Match type: $matchType, input: '$key'$normalizedLog → key: '${exactMatch.key}'")
            return exactMatch.value
        }

        // 2. Jaccard similarity (order-independent word matching)
        val inputWords = normalizedKey.split(" ").map { it.lowercase() }.filter { it.isNotBlank() }

        if (inputWords.size > 1) {
            // Find all candidates with their Jaccard similarity
            val jaccardCandidates = map.entries.mapNotNull { entry ->
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
                val normalizedLog = if (key != normalizedKey) " (normalized: '$normalizedKey')" else ""
                Log.d("AppMatcher", "  Match type: Jaccard (score: ${String.format("%.2f", bestJaccardMatch.score)}), input: '$key'$normalizedLog → key: '${bestJaccardMatch.entry.key}'")
                return bestJaccardMatch.entry.value
            }
        }

        // 3. Levenshtein distance (fallback for typos)
        val levenshteinMatch = findWithLevenshtein(normalizedKey, map)
        if (levenshteinMatch != null) {
            val (entry, distance) = levenshteinMatch
            val normalizedLog = if (key != normalizedKey) " (normalized: '$normalizedKey')" else ""
            Log.d("AppMatcher", "  Match type: Levenshtein (distance: $distance), input: '$key'$normalizedLog → key: '${entry.key}'")
            return entry.value
        }

        return null
    }

    /**
     * Normalize a name for consistent matching.
     * - Replace punctuation with spaces
     * - Trim leading/trailing whitespace
     * - Collapse multiple spaces to single space
     *
     * @param name The name to normalize
     * @return Normalized name
     */
    fun normalizeName(name: String): String {
        // Replace common punctuation with spaces
        val normalized = name.replace(Regex("[.,!?;:'\"\\(\\)\\[\\]\\{\\}<>\\\\/]"), " ")
        // Trim and collapse multiple spaces
        return normalized.trim().replace(Regex("\\s+"), " ")
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
     *
     * @param inputWords First word list
     * @param keyWords Second word list
     * @return Jaccard similarity coefficient [0, 1]
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
     * Find key using Levenshtein distance (edit distance).
     * Returns the candidate with the smallest edit distance (threshold: max 3 edits).
     *
     * Levenshtein distance measures the minimum number of single-character edits
     * (insertions, deletions, or substitutions) required to change one string into another.
     *
     * Examples:
     * - "Chrome" vs "Chrome" → 0
     * - "Chrome" vs "Chorme" → 2 (swap r-o, then o-r)
     * - "Chrome" vs "Chrme" → 1 (delete o)
     *
     * @param normalizedKey The normalized input key
     * @param map The map to search in
     * @return Pair of (entry, distance), or null if no match within threshold
     */
    private fun findWithLevenshtein(normalizedKey: String, map: Map<String, String>): Pair<Map.Entry<String, String>, Int>? {
        val threshold = 3  // Maximum allowed edit operations

        return map.entries
            .map { entry ->
                val distance = levenshtein(normalizedKey.lowercase(), entry.key.lowercase())
                entry to distance
            }
            .filter { (_, distance) -> distance <= threshold }
            .minByOrNull { (_, distance) -> distance }
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
}
