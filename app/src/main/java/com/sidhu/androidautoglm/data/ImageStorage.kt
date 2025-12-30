package com.sidhu.androidautoglm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages storage of images associated with chat messages.
 * Images are stored as PNG files in the app's internal storage directory.
 *
 * @param context Application context
 * @param messageDao Optional MessageDao for orphaned image cleanup on initialization
 */
class ImageStorage(
    private val context: Context,
    private val messageDao: com.sidhu.androidautoglm.data.dao.MessageDao? = null
) {

    private val imageDir: File = context.filesDir.resolve(IMAGE_DIR_NAME)
    private val cleanupScheduled = AtomicBoolean(false)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureImageDirExists()
        scheduleOrphanedCleanup()
    }

    private fun ensureImageDirExists() {
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }
    }

    private fun scheduleOrphanedCleanup() {
        // Only schedule cleanup once, and only if MessageDao is available
        if (messageDao != null && cleanupScheduled.compareAndSet(false, true)) {
            cleanupScope.launch {
                try {
                    cleanupOrphanedImages()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cleanup orphaned images", e)
                }
            }
        }
    }

    /**
     * Cleanup orphaned image files that have no corresponding message in the database.
     * This is called automatically on initialization if MessageDao is provided.
     */
    private suspend fun cleanupOrphanedImages() = withContext(Dispatchers.IO) {
        val dao = messageDao ?: return@withContext

        try {
            // Get all image paths from the database
            val validPaths = dao.getAllImagePaths().toSet()

            // Get all image files in the storage directory
            val allFiles = imageDir.listFiles()?.toList() ?: emptyList()

            // Delete files that are not in the database
            var deletedCount = 0
            for (file in allFiles) {
                if (file.isFile && !validPaths.contains(file.absolutePath)) {
                    try {
                        if (file.delete()) {
                            deletedCount++
                            Log.d(TAG, "Deleted orphaned image: ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete orphaned image: ${file.name}", e)
                    }
                }
            }

            if (deletedCount > 0) {
                Log.i(TAG, "Cleaned up $deletedCount orphaned image(s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during orphaned image cleanup", e)
        }
    }

    /**
     * Save a bitmap as a PNG file.
     * @param conversationId The conversation ID (used for tracking, file uses UUID)
     * @param bitmap The bitmap to save
     * @return The absolute path of the saved file, or null if saving failed
     */
    suspend fun saveImage(conversationId: Long, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val filename = "${UUID.randomUUID()}.png"
        val tempFile = File(imageDir, "$filename.tmp")
        val targetFile = File(imageDir, filename)

        try {
            // Write to temporary file first
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, IMAGE_COMPRESSION_QUALITY, out)
            }

            // Atomically rename to final filename
            if (tempFile.renameTo(targetFile)) {
                targetFile.absolutePath
            } else {
                Log.e(TAG, "Failed to rename temporary file: $tempFile")
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image for conversation $conversationId", e)
            tempFile.delete()
            null
        }
    }

    /**
     * Load a bitmap from a file path.
     * @return The bitmap, or null if the file doesn't exist or can't be decoded.
     */
    suspend fun loadImage(path: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image: $path", e)
            null
        }
    }

    /**
     * Delete all images for a specific conversation.
     */
    suspend fun deleteImagesForConversation(conversationId: Long, messageDao: com.sidhu.androidautoglm.data.dao.MessageDao) = withContext(Dispatchers.IO) {
        val messages = messageDao.getMessagesForConversationList(conversationId)
        messages.forEach { message ->
            message.imagePath?.let { path ->
                try {
                    File(path).delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete image: $path", e)
                }
            }
        }
    }

    /**
     * Delete an image file by path.
     */
    suspend fun deleteImage(path: String) = withContext(Dispatchers.IO) {
        try {
            File(path).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image: $path", e)
        }
    }

    /**
     * Get the total size of all stored images in bytes.
     */
    suspend fun getTotalStorageSize(): Long = withContext(Dispatchers.IO) {
        try {
            imageDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calculate storage size", e)
            0L
        }
    }

    /**
     * Delete all images in the storage directory.
     * Use with caution as this affects all conversations.
     */
    suspend fun deleteAllImages() = withContext(Dispatchers.IO) {
        try {
            imageDir.listFiles()?.forEach {
                try {
                    it.delete()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete image: ${it.absolutePath}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all images", e)
        }
    }

    /**
     * Load a scaled-down version of an image for thumbnail display.
     */
    suspend fun loadThumbnail(path: String, maxWidth: Int, maxHeight: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(path, options)

            // Calculate sample size
            options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
            options.inJustDecodeBounds = false

            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load thumbnail: $path", e)
            null
        }
    }

    /**
     * Calculate the appropriate sample size for loading a scaled bitmap.
     */
    private fun calculateInSampleSize(
        options: android.graphics.BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    companion object {
        private const val TAG = "ImageStorage"
        private const val IMAGE_DIR_NAME = "conversation_images"
        private const val IMAGE_COMPRESSION_QUALITY = 90
    }
}
