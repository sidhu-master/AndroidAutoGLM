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
     * 静态应用映射（借鉴 Open-AutoGLM apps.py），当 Launcher 查询不到时作为 fallback。
     * 覆盖常见中文应用及部分国际应用。
     */
    private val knownAliases = mapOf(
        // 社交通讯
        "微信" to "com.tencent.mm",
        "WeChat" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        // 电商购物
        "淘宝" to "com.taobao.taobao",
        "淘宝闪购" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "京东秒送" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "唯品会" to "com.vancl.mobile",
        "得物" to "com.shizhuang.duapp",
        "闲鱼" to "com.taobao.idlefish",
        "Temu" to "com.einnovation.temu",
        "temu" to "com.einnovation.temu",
        // 生活社区
        "小红书" to "com.xingin.xhs",
        "豆瓣" to "com.douban.frodo",
        "知乎" to "com.zhihu.android",
        // 地图导航
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "Google Maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "GoogleMaps" to "com.google.android.apps.maps",
        // 美食外卖
        "美团" to "com.sankuai.meituan",
        "大众点评" to "com.dianping.v1",
        "饿了么" to "me.ele",
        "肯德基" to "com.yek.android.kfc.activitys",
        "麦当劳" to "com.mcdonalds.app",
        "McDonald" to "com.mcdonalds.app",
        "mcdonald" to "com.mcdonalds.app",
        // 出行旅游
        "携程" to "ctrip.android.view",
        "铁路12306" to "com.MobileTicket",
        "12306" to "com.MobileTicket",
        "去哪儿" to "com.Qunar",
        "去哪儿旅行" to "com.Qunar",
        "滴滴出行" to "com.sdu.didi.psnger",
        // 视频娱乐
        "bilibili" to "tv.danmaku.bili",
        "B站" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "抖音短视频" to "com.ss.android.ugc.aweme",
        "抖音极速版" to "com.ss.android.ugc.aweme.lite",
        "快手" to "com.smile.gifmaker",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷视频" to "com.youku.phone",
        "芒果TV" to "com.hunantv.imgo.activity",
        "TikTok" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "Tiktok" to "com.zhiliaoapp.musically",
        // 音乐音频
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "汽水音乐" to "com.luna.music",
        "喜马拉雅" to "com.ximalaya.ting.android",
        // 阅读
        "番茄小说" to "com.dragon.read",
        "番茄免费小说" to "com.dragon.read",
        "七猫免费小说" to "com.kmxs.reader",
        // 效率工具
        "飞书" to "com.ss.android.lark",
        "QQ邮箱" to "com.tencent.androidqqmail",
        "Gmail" to "com.google.android.gm",
        "gmail" to "com.google.android.gm",
        "Chrome" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "Google Chrome" to "com.android.chrome",
        "WPS" to "cn.wps.moffice_eng",
        // AI 与工具
        "豆包" to "com.larus.nova",
        // 健康运动
        "keep" to "com.gotokeep.keep",
        "Keep" to "com.gotokeep.keep",
        "美柚" to "com.lingan.seeyou",
        // 新闻资讯
        "腾讯新闻" to "com.tencent.news",
        "今日头条" to "com.ss.android.article.news",
        // 房产
        "贝壳找房" to "com.lianjia.beike",
        "安居客" to "com.anjuke.android.app",
        // 金融
        "同花顺" to "com.hexin.plat.android",
        "支付宝" to "com.eg.android.AlipayGphone",
        // 游戏
        "星穹铁道" to "com.miHoYo.hkrpg",
        "崩坏：星穹铁道" to "com.miHoYo.hkrpg",
        "恋与深空" to "com.papegames.lysk.cn",
        // 系统与通用
        "设置" to "com.android.settings",
        "Settings" to "com.android.settings",
        "Android System Settings" to "com.android.settings",
        "AndroidSystemSettings" to "com.android.settings",
        "百度" to "com.baidu.searchbox",
        "Telegram" to "org.telegram.messenger",
        "WhatsApp" to "com.whatsapp",
        "Whatsapp" to "com.whatsapp",
        "Twitter" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "X" to "com.twitter.android",
        "Reddit" to "com.reddit.frontpage",
        "reddit" to "com.reddit.frontpage",
        "Duolingo" to "com.duolingo",
        "duolingo" to "com.duolingo",
        "VLC" to "org.videolan.vlc"
    )

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
        Log.d("AppMatcher", "Looking up: '$appName'")

        val normalized = normalizeName(appName)
        val source = dataSource
        if (source == null) {
            Log.w("AppMatcher", "dataSource is null")
            return null
        }

        // Check launcher/datasource first
        var result = findValue(source.map, appName)
        if (result == null) {
            (source as? AppMapper)?.refreshLauncherApps()
            result = findValue(source.map, appName)
        }
        if (result != null) {
            Log.d("AppMatcher", "Found: '$appName' -> '$result'")
            return result
        }

        knownAliases[normalized]?.let { return it }
        knownAliases.entries.find { it.key.contains(normalized) || normalized.contains(it.key) }?.let { return it.value }

        Log.w("AppMatcher", "Not found: '$appName'")
        return null
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

        // Check if input contains Chinese characters - skip Jaccard for Chinese
        val isChineseInput = containsChinese(normalizedKey)

        // 2. Jaccard similarity (order-independent word matching) - skip for Chinese
        if (!isChineseInput) {
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
        } else {
            Log.d("AppMatcher", "  Skipping Jaccard for Chinese input: '$normalizedKey'")
        }

        // 3. For Chinese: prefix/substring match (e.g. "抖音" matches "抖音短视频")
        // System app labels may be "抖音短视频" or "抖音极速版" while user says "抖音"
        if (isChineseInput && normalizedKey.length >= 2) {
            val substringMatches = map.entries.filter { entry ->
                entry.key.contains(normalizedKey) || normalizedKey.contains(entry.key)
            }
            if (substringMatches.isNotEmpty()) {
                // Prefer shortest key (most specific, e.g. "抖音" over "抖音短视频" when both match)
                val best = substringMatches.minByOrNull { it.key.length }!!
                Log.d("AppMatcher", "  Match type: Chinese prefix/substring, input: '$key' → key: '${best.key}'")
                return best.value
            }
        }

        // 4. Levenshtein distance - for English only
        val levenshteinMatch = if (isChineseInput) {
            Log.d("AppMatcher", "  Skipping Levenshtein for Chinese input: '$normalizedKey'")
            null
        } else {
            findWithLevenshtein(normalizedKey, map)
        }
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
     * Find a key using Levenshtein distance (edit distance).
     * Returns the candidate with the smallest edit distance (threshold: max 2 edits for Chinese, 3 for English).
     *
     * Levenshtein distance measures the minimum number of single-character edits
     * (insertions, deletions, or substitutions) required to change one string into another.
     *
     * For Chinese characters, we use a stricter threshold (2) because character-by-character
     * comparison doesn't make sense for Chinese app names.
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
        // For Chinese input, use stricter threshold (2) and skip if length difference is too large
        val isChineseInput = containsChinese(normalizedKey)
        val threshold = if (isChineseInput) 2 else 3

        // For Chinese input, also check that length is similar (within 50% difference)
        if (isChineseInput) {
            val inputLength = normalizedKey.length
            val chineseMatches = map.entries.filter { entry ->
                val keyLength = entry.key.length
                // Length must be similar for Chinese (within 50%)
                val lengthRatio = maxOf(inputLength, keyLength).toDouble() / minOf(inputLength, keyLength)
                lengthRatio <= 1.5
            }

            if (chineseMatches.isEmpty()) {
                Log.d("AppMatcher", "  No length-matching Chinese candidates found")
                return null
            }

            return chineseMatches
                .map { entry ->
                    val distance = levenshtein(normalizedKey.lowercase(), entry.key.lowercase())
                    entry to distance
                }
                .filter { (_, distance) -> distance <= threshold }
                .minByOrNull { (_, distance) -> distance }
        }

        // Original logic for English
        return map.entries
            .map { entry ->
                val distance = levenshtein(normalizedKey.lowercase(), entry.key.lowercase())
                entry to distance
            }
            .filter { (_, distance) -> distance <= threshold }
            .minByOrNull { (_, distance) -> distance }
    }

    /**
     * Check if a string contains Chinese (CJK) characters.
     */
    private fun containsChinese(str: String): Boolean {
        return str.any { char ->
            Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            Character.UnicodeBlock.of(char) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            // Also check for common Chinese punctuation
            char in listOf('，', '。', '！', '？', '：', '；', '、', '「', '」', '『', '』', '（', '）', '【', '】')
        }
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
