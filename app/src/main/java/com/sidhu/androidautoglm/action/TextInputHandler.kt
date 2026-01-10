package com.sidhu.androidautoglm.action

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.sidhu.androidautoglm.AutoGLMService
import com.sidhu.androidautoglm.BuildConfig
import kotlinx.coroutines.delay

/**
 * Handles text input with focus management, verification, and fallback mechanisms.
 *
 * This class provides reliable text input functionality by:
 * - Ensuring an editable field is focused before input
 * - Verifying text was successfully entered
 * - Falling back to clipboard paste when direct text setting fails
 * - Handling character encoding (CJK, emoji) and max length limits
 */
class TextInputHandler(private val service: AutoGLMService) {

    companion object {
        private const val TAG = "TextInputHandler"
        private const val DEFAULT_VERIFY_DELAY_MS = 150L      // Wait for text to be processed
        private const val VISUAL_INPUT_FAILED_STATUS = "未输入正确的文本"
    }

    private val keyboardAgent by lazy { com.sidhu.autoinput.KeyboardAgent(service) }

    fun setDebugListener(listener: (android.graphics.Bitmap, String) -> Unit) {
        keyboardAgent.onDebugInfo = listener
        com.sidhu.autoinput.KeyboardScanner.setCandidateDebugListener(listener)
    }

    /**
     * Result of a text input verification
     */
    sealed class VerificationResult {
        data object FullMatch : VerificationResult()
        data object PartialMatch : VerificationResult()
        data object NoMatch : VerificationResult()
    }

    /**
     * Finds the currently focused editable node in the accessibility tree
     */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeRecursive(root) { it.isEditable && it.isFocused }
    }

    /**
     * Finds the first editable node in the accessibility tree
     */
    private fun findEditableNode(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findNodeRecursive(root) { it.isEditable }
    }

    /**
     * Generic recursive node search.
     * @param node The root node to start searching from
     * @param predicate The condition to match for the desired node
     * @return The first matching node, or null if not found
     */
    private fun findNodeRecursive(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        // Check if this node matches the predicate
        if (predicate(node)) {
            return node
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeRecursive(child, predicate)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Attempts to focus the given editable node
     * @return true if focus was successful or already focused
     */
    private suspend fun focusEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocused) {
            Log.d(TAG, "Node already focused")
            return true
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (success) {
            delay(100) // Wait for focus to take effect
            Log.d(TAG, "Focus action performed successfully")
        } else {
            Log.w(TAG, "Failed to focus node")
        }
        return success
    }

    /**
     * Ensures an editable field is focused before text input.
     * First checks for a focused editable node, then searches for one if none is focused.
     * @return The focused editable node, or null if none found
     */
    private suspend fun ensureFocusedEditableNode(): AccessibilityNodeInfo? {
        // First check if there's already a focused editable node
        val focusedNode = findFocusedEditableNode()
        if (focusedNode != null) {
            Log.d(TAG, "Found focused editable node")
            return focusedNode
        }

        // No focused editable node, search for and focus the first editable one
        Log.d(TAG, "No focused editable node, searching for editable node...")
        val editableNode = findEditableNode()
        if (editableNode != null) {
            val focusSuccess = focusEditableNode(editableNode)
            if (focusSuccess) {
                return editableNode
            } else {
                Log.w(TAG, "Found editable node but failed to focus it")
            }
        } else {
            Log.e(TAG, "No editable node found")
        }

        return null
    }

    /**
     * Gets the maximum text length for a node, if available
     * @return Max length, or null if not specified
     */
    private fun getMaxLength(node: AccessibilityNodeInfo): Int? {
        return try {
            val maxLength = node.maxTextLength
            if (maxLength > 0) maxLength else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get max length: ${e.message}")
            null
        }
    }

    /**
     * Truncates text to the specified maximum length
     */
    private fun truncateToMaxLength(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength)
        } else {
            text
        }
    }

    /**
     * Verifies that the text was successfully entered by reading back the field content.
     * Uses node.refresh() to get the latest content after text input.
     * @return VerificationResult: FullMatch, PartialMatch, or NoMatch
     */
    private suspend fun verifyTextContent(node: AccessibilityNodeInfo, expectedText: String): VerificationResult {
        delay(DEFAULT_VERIFY_DELAY_MS)

        try {
            if (node.refresh()) {
                val actualText = node.text?.toString()
                if (actualText != null) {
                    return when {
                        actualText == expectedText -> {
                            Log.d(TAG, "Exact match verified")
                            VerificationResult.FullMatch
                        }
                        actualText.isEmpty() && expectedText.isNotEmpty() -> {
                            Log.d(TAG, "No match: expected='$expectedText', actual='$actualText'")
                            VerificationResult.NoMatch
                        }
                        expectedText.startsWith(actualText) -> {
                            Log.d(TAG, "Partial match: ${actualText.length}/${expectedText.length} chars")
                            VerificationResult.PartialMatch
                        }
                        actualText.startsWith(expectedText) -> {
                            // Text has extra content (autocomplete, formatting, etc.)
                            Log.d(TAG, "Full match with extra content: '$actualText'")
                            VerificationResult.FullMatch
                        }
                        else -> {
                            Log.d(TAG, "No match: expected='$expectedText', actual='$actualText'")
                            VerificationResult.NoMatch
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to get text from node")
                    return VerificationResult.NoMatch
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh node: ${e.message}")
        }

        Log.w(TAG, "Verification failed for expected: '$expectedText'")
        return VerificationResult.NoMatch
    }

    /**
     * Sets text on a node with ACTION_SET_TEXT and performs a single quick verification.
     * Note: ACTION_SET_TEXT replaces all existing text, no need to clear first
     */
    private suspend fun setTextWithVerification(node: AccessibilityNodeInfo, text: String): Boolean {
        // ACTION_SET_TEXT replaces all existing text
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,  text)

        val performSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!performSuccess) {
            Log.w(TAG, "ACTION_SET_TEXT returned false")
            return false
        }

        // Single quick verification (no retries to avoid redundancy with fallback)
        delay(DEFAULT_VERIFY_DELAY_MS)

        return verifyTextContent(node, text) != VerificationResult.NoMatch
    }

    /**
     * Pastes text using clipboard and ACTION_PASTE
     * Note: This is only called when SET_TEXT truly failed, so we clear first
     *
     * @param node The target node
     * @param text The text to paste (will be copied to clipboard)
     */
    private suspend fun pasteText(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            // Clear the field first (since SET_TEXT failed and there might be old content)
            val clearArgs = Bundle()
            clearArgs.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            delay(50)
            Log.d(TAG, "Cleared field before paste")

            // Set clipboard content
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: run {
                    Log.e(TAG, "ClipboardManager not available")
                    return false
                }

            val clip = ClipData.newPlainText("auto-glm-input", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Text copied to clipboard")

            // Perform paste action
            val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasteSuccess) {
                Log.w(TAG, "ACTION_PASTE returned false")
                return false
            }

            // Verify
            return verifyTextContent(node, text) != VerificationResult.NoMatch
        } catch (e: Exception) {
            Log.e(TAG, "Paste failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Main entry point: inputs text with focus management, verification, and fallback.
     *
     * @param text The text to input
     * @return true if text input was successful (full or partial)
     */
    suspend fun inputText(text: String): Boolean {
        Log.d("VisualInputTest", ">>> Start inputText: '$text'")
        if (text.isEmpty()) {
            Log.w(TAG, "Empty text provided")
            return false
        }

        Log.d(TAG, "Inputting text: '$text' (${text.length} chars)")

        // Ensure we have a focused editable node
        val targetNode = ensureFocusedEditableNode()
        if (targetNode == null) {
            Log.w(TAG, "No focused editable node available. Will attempt Visual Input.")
            Log.d("VisualInputTest", "TargetNode is null, proceeding with Visual Input")
        } else {
            if (BuildConfig.AUTO_INPUT_DEV_MODE) {
                Log.d("VisualInputTest", "TargetNode found, but dev mode forces Visual Input")
            } else {
                Log.d("VisualInputTest", "TargetNode found, using Accessibility text input first")
            }
        }

        // Truncate if max length is known (only possible if node found)
        val textToSet = if (targetNode != null) {
            val maxLength = getMaxLength(targetNode)
             if (maxLength != null && maxLength < text.length) {
                Log.d(TAG, "Text truncated to max length $maxLength")
                truncateToMaxLength(text, maxLength)
            } else {
                text
            }
        } else {
            text
        }

        if (!BuildConfig.AUTO_INPUT_DEV_MODE && targetNode != null) {
            Log.d(TAG, "Attempting Accessibility input: ACTION_SET_TEXT")
            Log.d("VisualInputTest", "Attempting Accessibility: SET_TEXT")
            if (setTextWithVerification(targetNode, textToSet)) {
                Log.d(TAG, "Text input successful via ACTION_SET_TEXT")
                Log.d("VisualInputTest", "Accessibility SET_TEXT Success")
                return true
            }

            Log.w(TAG, "ACTION_SET_TEXT failed, falling back to clipboard paste")
            Log.d("VisualInputTest", "Attempting Accessibility: PASTE")
            if (pasteText(targetNode, textToSet)) {
                Log.d(TAG, "Text input successful via ACTION_PASTE fallback")
                Log.d("VisualInputTest", "Accessibility PASTE Success")
                return true
            }

            Log.e(TAG, "Accessibility text input failed")
            Log.e("VisualInputTest", "Accessibility methods failed.")
            return false
        }

        val method = if (BuildConfig.AUTO_INPUT_DEV_MODE) {
            "Visual Keyboard Simulation (Dev Mode)"
        } else {
            "Visual Keyboard Simulation"
        }
        Log.i(TAG, "Starting Text Input. Method: $method")
        Log.d("VisualInputTest", "Preparing for Visual Input. Moving window...")

        service.floatingWindowController?.moveWindowToTop()
        delay(300)

        Log.d("VisualInputTest", "Taking screenshot for keyboard scan...")
        val screenshot = service.takeScreenshot(timeoutMs = 2000)

        if (screenshot == null) {
            Log.e(TAG, "Screenshot failed, cannot perform Visual Input")
            Log.e("VisualInputTest", "Screenshot was NULL.")
            service.updateFloatingStatus(VISUAL_INPUT_FAILED_STATUS)
            service.floatingWindowController?.setTaskRunning(false)
            return false
        }

        Log.d("VisualInputTest", "Screenshot captured successfully (${screenshot.width}x${screenshot.height}). Calling KeyboardAgent...")
        val ok = keyboardAgent.type(
            textToSet,
            screenshot,
            screenshotProvider = {
                Log.d("VisualInputTest", "Provider: Capturing new screenshot for candidates...")
                Log.d(TAG, "Capturing new screenshot for candidate selection...")
                service.takeScreenshot(timeoutMs = 2000)
            }
        )
        if (ok) {
            Log.d(TAG, "Text input successful via Keyboard Agent")
            Log.d("VisualInputTest", "KeyboardAgent.type returned TRUE. Visual Input Success.")
            delay(500)
            return true
        }

        Log.w(TAG, "Keyboard Agent failed")
        Log.d("VisualInputTest", "KeyboardAgent.type returned FALSE. Visual Input Failed.")
        service.updateFloatingStatus(VISUAL_INPUT_FAILED_STATUS)
        service.floatingWindowController?.setTaskRunning(false)
        return false
    }
}
