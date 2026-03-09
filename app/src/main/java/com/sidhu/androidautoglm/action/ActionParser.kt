package com.sidhu.androidautoglm.action

import android.util.Log
import com.sidhu.androidautoglm.vision.MasterAction

sealed class Action {
    /** 普通点击；confirmationMessage 非空时表示敏感操作需用户确认 */
    data class Tap(val x: Int, val y: Int, val confirmationMessage: String? = null) : Action()
    data class DoubleTap(val x: Int, val y: Int) : Action()
    data class LongPress(val x: Int, val y: Int) : Action()
    data class Swipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int) : Action()
    data class Type(val text: String) : Action()
    data class Launch(val appName: String) : Action()
    object Back : Action()
    object Home : Action()
    data class Wait(val durationMs: Long) : Action()
    data class Finish(val message: String) : Action()
    /** 登录/验证码等需用户协助 */
    data class TakeOver(val message: String) : Action()
    /** 多个选项时需用户选择 */
    object Interact : Action()
    /** 记录页面内容（占位，无实际操作） */
    object Note : Action()
    /** 总结/评论页面（占位，无实际操作） */
    data class CallApi(val instruction: String) : Action()
    data class Error(val reason: String) : Action()
    object Unknown : Action()
}

object ActionParser {

    // 正则编译开销较大，提升为常量，整个生命周期只编译一次
    private val FINISH_REGEX = Regex(
        """finish\s*\(\s*message\s*=\s*["']([\s\S]*?)["']\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val DO_REGEX = Regex(
        """do\s*\((.*)\)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 找到文本中要执行的 action 的起始位置。
     * - 若只有 do 或只有 finish：取最后一个（支持 fallback 场景）
     * - 若同时有 do 和 finish：优先取最后一个 do，避免模型在未执行 Type/发送 时就 finish
     *   （常见：模型输出 do(Type) 和 finish 同句，误以为已发送，实际未执行）
     * @return 起始索引，未找到返回 -1
     */
    private fun findActionStart(text: String): Int {
        val lastDo = text.lastIndexOf("do(")
        val lastFinish = text.lastIndexOf("finish(")
        return when {
            lastDo < 0 && lastFinish < 0 -> -1
            lastDo < 0 -> lastFinish
            lastFinish < 0 -> lastDo
            // 同时存在：优先执行 do，避免过早 finish（如 Type+finish 时先执行 Type）
            else -> lastDo
        }
    }

    /**
     * Parses a full response string into an Action object for execution.
     * First extracts the action part, then parses it.
     *
     * @param response The full response string (may contain thinking + action)
     * @param screenWidth Screen width for coordinate conversion
     * @param screenHeight Screen height for coordinate conversion
     * @return Parsed Action object
     */
    fun parse(response: String, screenWidth: Int, screenHeight: Int): Action {
        val cleanResponse = response.trim()
        Log.d("ActionParser", "Parsing: $cleanResponse")

        // Extract the LAST action (model may output fallback after "上一个动作执行失败")
        val actionStart = findActionStart(cleanResponse)

        val actionString = if (actionStart >= 0) {
            cleanResponse.substring(actionStart).trim()
        } else {
            // No action found, treat entire response as finish message
            return Action.Finish(cleanResponse)
        }

        // Now parse the extracted action string
        return parseAction(actionString, screenWidth, screenHeight)
    }

    private fun parseTapAction(element: List<*>?, screenWidth: Int, screenHeight: Int, factory: (Int, Int) -> Action): Action {
        if (element != null && element.size >= 2) {
            val relX = (element[0] as Number).toFloat()
            val relY = (element[1] as Number).toFloat()
            val absX = (relX / 1000f * screenWidth).toInt()
            val absY = (relY / 1000f * screenHeight).toInt()

            Log.d("ActionParser", "Action Analysis - Screen: ${screenWidth}x${screenHeight}")
            Log.d("ActionParser", "Action Analysis - Relative: ($relX, $relY)")
            Log.d("ActionParser", "Action Analysis - Absolute: ($absX, $absY)")

            return factory(absX, absY)
        } else if (element != null && element.size == 1) {
                // Some responses might use a single integer index? Unlikely for AutoGLM but just in case
                return Action.Error("Invalid element format: $element")
        } else {
            // Fallback: Check if it's a "box" format [y1, x1, y2, x2]
            if (element != null && element.size == 4) {
                    // Box format [y1, x1, y2, x2] -> click center
                val y1 = (element[0] as Number).toFloat()
                val x1 = (element[1] as Number).toFloat()
                val y2 = (element[2] as Number).toFloat()
                val x2 = (element[3] as Number).toFloat()

                val centerX = (x1 + x2) / 2
                val centerY = (y1 + y2) / 2

                return factory(
                    (centerX / 1000f * screenWidth).toInt(),
                    (centerY / 1000f * screenHeight).toInt()
                )
            } else {
                return Action.Error("Invalid element for action")
            }
        }
    }

    /**
     * Parses response into thinking and ParsedAction for display.
     * Extracts the LAST complete do(...) or finish(...) block (same as extractActionString).
     *
     * @param content The raw response content
     * @return Pair of (thinking, ParsedAction?) where ParsedAction is null if no valid action found
     */
    fun parseResponsePartsToParsedAction(content: String): Pair<String, ParsedAction?> {
        val trimmedContent = content.trim()

        val actionStart = findActionStart(trimmedContent)

        if (actionStart < 0) {
            // No action found, treat entire content as thinking
            return Pair(trimmedContent, null)
        }

        // Extract thinking (everything before the action)
        val thinking = trimmedContent.substring(0, actionStart).trim()

        // Find the opening parenthesis position
        val openParenPos = trimmedContent.indexOf('(', actionStart)
        if (openParenPos < 0) {
            // Malformed action, return thinking only
            return Pair(thinking, null)
        }

        // Find the end of the action block by matching parentheses
        val actionEnd = findMatchingParenthesis(trimmedContent, openParenPos + 1)

        val actionString = if (actionEnd >= 0 && actionEnd < trimmedContent.length) {
            trimmedContent.substring(actionStart, actionEnd + 1).trim()
        } else {
            trimmedContent.substring(actionStart).trim()
        }

        // Parse the action string into ParsedAction
        val parsedAction = parseToParsedAction(actionString)

        return Pair(thinking, parsedAction)
    }

    /**
     * Extracts the raw action string from content for logging and execution.
     * Returns the LAST complete do(...) or finish(...) block, or empty string if not found.
     *
     * Uses LAST occurrence because when previous action fails, the model often outputs:
     * "do(action=\"Launch\", app=\"抖音\")\n上一个动作执行失败。\ndo(action=\"Tap\", element=[435,856])"
     * We should execute the Tap (the fallback), not the failed Launch.
     *
     * @param content The raw response content
     * @return The extracted action string, or empty string if no action found
     */
    fun extractActionString(content: String): String {
        val trimmedContent = content.trim()
        val actionStart = findActionStart(trimmedContent)

        if (actionStart < 0) return ""

        val openParenPos = trimmedContent.indexOf('(', actionStart)
        if (openParenPos < 0) return trimmedContent.substring(actionStart).trim()

        val actionEnd = findMatchingParenthesis(trimmedContent, openParenPos + 1)
        return if (actionEnd >= 0 && actionEnd < trimmedContent.length) {
            trimmedContent.substring(actionStart, actionEnd + 1).trim()
        } else {
            trimmedContent.substring(actionStart).trim()
        }
    }

    /**
     * Parses an action string into a ParsedAction object for display.
     * Handles both do(action="...", ...) and finish(message="...") formats.
     *
     * @param actionString The action string to parse
     * @return ParsedAction, or null if parsing fails
     */
    private fun parseToParsedAction(actionString: String): ParsedAction? {
        val cleanAction = actionString.trim()

        // 1. Try to match finish(message="...") - [\s\S]*? 支持 message 内换行
        FINISH_REGEX.find(cleanAction)?.let {
            return ParsedAction(
                type = ActionType.FINISH,
                rawParams = mapOf("message" to it.groupValues[1]),
                normalizedParams = mapOf("message" to it.groupValues[1])
            )
        }

        // 2. Try to match do(action="...", ...)
        val doMatch = DO_REGEX.find(cleanAction)

        if (doMatch != null) {
            val args = doMatch.groupValues[1]
            val (rawParams, normalizedParams) = parseActionParams(args)

            val actionTypeStr = rawParams["action"] ?: return null
            val actionType = actionTypeStr.toActionType()

            return ParsedAction(
                type = actionType,
                rawParams = rawParams,
                normalizedParams = normalizedParams
            )
        }

        // 3. No recognized action format
        return null
    }

    /**
     * Unified parameter parsing function.
     * Extracts both string and list parameters from action argument string.
     *
     * @param args The argument string (e.g., 'action="Tap", element=[500, 750]')
     * @return Pair of (raw string params, normalized params with proper types)
     */
    fun parseActionParams(args: String): Pair<Map<String, String>, Map<String, Any>> {
        val rawParams = mutableMapOf<String, String>()
        val normalizedParams = mutableMapOf<String, Any>()

        // Regex to match key="value" or key='value'
        val stringParam = Regex("""(\w+)\s*=\s*["'](.*?)["']""")
        stringParam.findAll(args).forEach {
            val key = it.groupValues[1]
            val value = it.groupValues[2]
            rawParams[key] = value
            normalizedParams[key] = value
        }

        // key = [123, 456] or key = (123, 456) or key=[123,456]
        val listParam = Regex("""(\w+)\s*=\s*[\[\(]([\d\s,.]+)[\]\)]""")
        listParam.findAll(args).forEach { match ->
            val key = match.groupValues[1]
            val listStr = match.groupValues[2]
            val list = listStr.split(",").mapNotNull { it.trim().toFloatOrNull() }
            // Store as integer format for display (e.g., "[500, 750]" instead of "[500.0, 750.0]")
            rawParams[key] = "[${list.joinToString(", ") { it.toInt().toString() }}]"
            normalizedParams[key] = list
        }

        return Pair(rawParams, normalizedParams)
    }

    /**
     * Finds the position of the closing parenthesis that matches the opening one.
     * Handles nested parentheses within parameters (e.g., arrays, nested calls).
     *
     * @param text The text to search in
     * @param startFrom The position to start searching from (should be right after the opening "(")
     * @return The position of the matching ")", or -1 if not found
     */
    private fun findMatchingParenthesis(text: String, startFrom: Int): Int {
        var depth = 1
        var i = startFrom

        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }

        return if (depth == 0) i - 1 else -1
    }

    /**
     * Parses an action string (already extracted from response) into an Action object.
     * The actionString should be like "do(action="Tap", element=[500, 750])"
     * or "finish(message="Done")".
     *
     * @param actionString The extracted action string (e.g., "do(action="Tap", ...)")
     * @param screenWidth Screen width for coordinate conversion
     * @param screenHeight Screen height for coordinate conversion
     * @return Parsed Action object
     */
    fun parseAction(actionString: String, screenWidth: Int, screenHeight: Int): Action {
        val cleanAction = actionString.trim()
        Log.d("ActionParser", "Parsing action: $cleanAction")

        // 1. Try to match finish(message="...") - DOT_MATCHES_ALL 支持 message 内换行
        FINISH_REGEX.find(cleanAction)?.let {
            return Action.Finish(it.groupValues[1])
        }

        // 2. Try to match do(action="...", ...)
        val doMatch = DO_REGEX.find(cleanAction)

        if (doMatch != null) {
            val args = doMatch.groupValues[1]
            val params = parseParams(args)

            val actionType = params["action"]?.toString() ?: return Action.Error("Missing action type")

            return when (actionType.lowercase()) {
                "tap" -> {
                    val element = params["element"] as? List<*>
                    val msg = params["message"]?.toString()
                    parseTapAction(element, screenWidth, screenHeight) { x, y -> Action.Tap(x, y, msg) }
                }
                "double tap" -> {
                    val element = params["element"] as? List<*>
                    parseTapAction(element, screenWidth, screenHeight) { x, y -> Action.DoubleTap(x, y) }
                }
                "long press" -> {
                    val element = params["element"] as? List<*>
                    parseTapAction(element, screenWidth, screenHeight) { x, y -> Action.LongPress(x, y) }
                }
                "swipe" -> {
                    val start = params["start"] as? List<*>
                    val end = params["end"] as? List<*>
                    if (start != null && end != null && start.size >= 2 && end.size >= 2) {
                        val sx = (start[0] as Number).toFloat()
                        val sy = (start[1] as Number).toFloat()
                        val ex = (end[0] as Number).toFloat()
                        val ey = (end[1] as Number).toFloat()
                        Action.Swipe(
                            (sx / 1000f * screenWidth).toInt(),
                            (sy / 1000f * screenHeight).toInt(),
                            (ex / 1000f * screenWidth).toInt(),
                            (ey / 1000f * screenHeight).toInt()
                        )
                    } else {
                        Action.Error("Invalid coordinates for Swipe")
                    }
                }
                "type", "type_name" -> {
                    val text = params["text"]?.toString() ?: return Action.Error("Missing text for Type")
                    Action.Type(text)
                }
                "launch" -> {
                    val app = params["app"]?.toString() ?: return Action.Error("Missing app name for Launch")
                    Action.Launch(app)
                }
                "back" -> Action.Back
                "home" -> Action.Home
                "wait" -> {
                    val durationStr = params["duration"]?.toString() ?: "1"
                    val durationSeconds = durationStr.replace("seconds", "").trim().toDoubleOrNull() ?: 1.0
                    Action.Wait((durationSeconds * 1000).toLong())
                }
                "take_over" -> {
                    val msg = params["message"]?.toString() ?: "需要用户协助"
                    Action.TakeOver(msg)
                }
                "interact" -> Action.Interact
                "note" -> Action.Note
                "call_api" -> {
                    val instruction = params["instruction"]?.toString() ?: ""
                    Action.CallApi(instruction)
                }
                else -> Action.Unknown
            }
        }

        // 3. No recognized action format
        return Action.Error("Unknown action format: $cleanAction")
    }

    private fun parseParams(args: String): Map<String, Any> {
        return parseActionParams(args).second
    }

    // -----------------------------------------------------------------------
    // New architecture: parse master AI output into MasterAction (target-based)
    // -----------------------------------------------------------------------

    /**
     * Parse the master AI's response into a [MasterAction].
     * Supports both the new target/direction format and legacy element/coordinate format.
     */
    fun parseMasterAction(response: String): MasterAction {
        val actionStr = extractActionString(response)
        if (actionStr.isBlank()) {
            return MasterAction.Finish(response.trim())
        }
        return parseMasterActionString(actionStr)
    }

    private fun parseMasterActionString(actionString: String): MasterAction {
        val clean = actionString.trim()

        FINISH_REGEX.find(clean)?.let {
            return MasterAction.Finish(it.groupValues[1])
        }

        val doMatch = DO_REGEX.find(clean) ?: return MasterAction.Error("Unknown format: $clean")
        val args = doMatch.groupValues[1]
        val (rawParams, _) = parseActionParams(args)

        val actionType = rawParams["action"] ?: return MasterAction.Error("Missing action type")

        return when (actionType.lowercase()) {
            "tap" -> {
                val target = rawParams["target"]
                if (target != null) MasterAction.Tap(target)
                else MasterAction.Error("Missing target for Tap")
            }
            "double tap" -> {
                val target = rawParams["target"]
                if (target != null) MasterAction.DoubleTap(target)
                else MasterAction.Error("Missing target for Double Tap")
            }
            "long press" -> {
                val target = rawParams["target"]
                if (target != null) MasterAction.LongPress(target)
                else MasterAction.Error("Missing target for Long Press")
            }
            "swipe" -> {
                val direction = rawParams["direction"]
                if (direction != null) MasterAction.Swipe(direction)
                else MasterAction.Error("Missing direction for Swipe")
            }
            "type", "type_name" -> {
                val text = rawParams["text"] ?: return MasterAction.Error("Missing text for Type")
                MasterAction.Type(text)
            }
            "launch" -> {
                val app = rawParams["app"] ?: return MasterAction.Error("Missing app for Launch")
                MasterAction.Launch(app)
            }
            "back" -> MasterAction.Back
            "home" -> MasterAction.Home
            "wait" -> {
                val dur = rawParams["duration"]?.replace("seconds", "")?.trim()?.toFloatOrNull() ?: 1f
                MasterAction.Wait(dur)
            }
            else -> MasterAction.Unknown
        }
    }
}
