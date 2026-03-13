package com.sidhu.androidautoglm.vision

import android.graphics.Bitmap

/**
 * Abstraction for visual understanding of screen content.
 * Uses remote model vision API (MiniMax, Gemini, Doubao, etc.).
 */
interface VisionEngine {

    /** One-time initialization (model loading, warm-up). */
    suspend fun initialize()

    /** Whether the engine is ready for inference. */
    fun isReady(): Boolean

    /**
     * Generate a concise textual description of the current screen.
     * @return ~100-200 char description including app name, page type, key UI elements, and visible text.
     */
    suspend fun describeScreen(bitmap: Bitmap): String

    /**
     * Locate a UI element described by [target] and return its center coordinates.
     * @return Pair(x, y) in relative 0-999 coordinate space, or null if not found.
     */
    suspend fun locateElement(bitmap: Bitmap, target: String): Pair<Int, Int>?

    /**
     * Answer a free-form visual question about the screen (e.g. "输入框中的文字是什么").
     */
    suspend fun verifyContent(bitmap: Bitmap, query: String): String

    /** Release all native resources. */
    fun destroy()
}
