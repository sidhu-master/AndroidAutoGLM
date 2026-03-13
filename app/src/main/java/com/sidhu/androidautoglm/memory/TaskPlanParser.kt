package com.sidhu.androidautoglm.memory

import android.util.Log

/**
 * TaskPlanParser - 解析AI响应中的任务计划标签
 *
 * 解析格式：
 * <task_plan>
 * # 任务：[任务简述]
 * - [ ] 步骤1：具体描述
 * - [x] 步骤2：具体描述
 * - [/] 步骤3：具体描述（进行中）
 * </task_plan>
 *
 * 状态标记：
 * - [ ] 待完成
 * - [x] 已完成
 * - [/] 进行中
 */
object TaskPlanParser {

    private const val TAG = "TaskPlanParser"

    // 正则表达式匹配 <task_plan>...</task_plan> 标签
    private val TASK_PLAN_PATTERN = Regex(
        "<task_plan>([\\s\\S]*?)</task_plan>",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从AI响应文本中解析任务计划
     * @param responseText AI的响应文本（包含<think>标签的内容）
     * @return 解析后的任务计划内容，如果不存在则返回null
     */
    fun parse(responseText: String): String? {
        if (responseText.isBlank()) {
            return null
        }

        // 查找 <task_plan> 标签
        val match = TASK_PLAN_PATTERN.find(responseText)
        if (match == null) {
            Log.d(TAG, "No <task_plan> tag found in response")
            return null
        }

        val taskPlanContent = match.groupValues.getOrNull(1)?.trim()
        if (taskPlanContent.isNullOrBlank()) {
            Log.d(TAG, "Empty task plan content")
            return null
        }

        // 验证内容是否包含有效的任务计划格式
        if (!isValidTaskPlan(taskPlanContent)) {
            Log.d(TAG, "Invalid task plan format")
            return null
        }

        Log.d(TAG, "Successfully parsed task plan")
        return formatTaskPlan(taskPlanContent)
    }

    /**
     * 验证任务计划格式是否有效
     */
    private fun isValidTaskPlan(content: String): Boolean {
        // 至少应该包含任务标题或步骤列表
        return content.contains("# 任务") ||
                content.contains("## 任务") ||
                content.contains("- [") ||
                content.contains("- [x]") ||
                content.contains("- [/]")
    }

    /**
     * 格式化任务计划内容
     * 确保格式规范统一
     */
    private fun formatTaskPlan(content: String): String {
        val lines = content.lines()
        val formattedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }

            // 处理状态标记，统一格式
            val formattedLine = when {
                trimmed.startsWith("- [ ]") -> trimmed
                trimmed.startsWith("- [x]") -> trimmed
                trimmed.startsWith("- [/]") -> trimmed
                trimmed.startsWith("- [X]") -> trimmed.replace("- [X]", "- [x]")
                else -> trimmed
            }
            formattedLines.add(formattedLine)
        }

        // 确保以任务标题开始
        if (formattedLines.isNotEmpty() && !formattedLines[0].startsWith("#")) {
            // 添加默认标题
            formattedLines.add(0, "# 任务进度")
        }

        return formattedLines.joinToString("\n")
    }

    /**
     * 从任务计划内容中提取当前步骤
     * @param taskPlanContent 任务计划内容
     * @return 当前进行中的步骤描述，如果没有则返回null
     */
    fun getCurrentStep(taskPlanContent: String): String? {
        val lines = taskPlanContent.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- [/]")) {
                // 提取步骤描述（去掉状态标记）
                return trimmed
                    .replace(Regex("^-\\s*\\[/]\\s*"), "")
                    .trim()
            }
        }
        return null
    }

    /**
     * 获取任务进度统计
     * @return Pair(已完成数, 总步骤数)
     */
    fun getProgress(taskPlanContent: String): Pair<Int, Int> {
        val lines = taskPlanContent.lines()
        var completed = 0
        var total = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- [")) {
                total++
                if (trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]")) {
                    completed++
                }
            }
        }

        return Pair(completed, total)
    }

    /**
     * 检查任务是否完成
     */
    fun isTaskCompleted(taskPlanContent: String): Boolean {
        val (completed, total) = getProgress(taskPlanContent)
        if (total == 0) return false
        return completed == total
    }
}
