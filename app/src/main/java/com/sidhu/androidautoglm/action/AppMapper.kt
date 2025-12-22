package com.sidhu.androidautoglm.action

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File

object AppMapper {
    private var appMap: Map<String, String> = emptyMap()
    private const val CONFIG_FILE_NAME = "app_map.json"
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        loadMap()
    }

    fun reload() {
        loadMap()
    }

    private fun loadMap() {
        val context = appContext ?: return
        val gson = Gson()
        val type = object : TypeToken<Map<String, String>>() {}.type
        val externalFile = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)

        try {
            val json = if (externalFile.exists()) {
                Log.d("AppMapper", "Loading app map from external file.")
                externalFile.readText()
            } else {
                Log.d("AppMapper", "External file not found, creating from assets.")
                context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { it.readText() }.also {
                    externalFile.writeText(it)
                }
            }
            appMap = gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e("AppMapper", "Error loading app map, falling back to assets", e)
            try {
                context.assets.open(CONFIG_FILE_NAME).bufferedReader().use { reader ->
                    appMap = gson.fromJson(reader, type) ?: emptyMap()
                }
            } catch (e2: Exception) {
                Log.e("AppMapper", "FATAL: Could not load app map from assets either.", e2)
                appMap = emptyMap() // Critical failure
            }
        }
    }

    fun getAppMapJson(): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(appMap)
    }

    fun saveAppMap(jsonString: String): Boolean {
        val context = appContext ?: return false
        val gson = Gson()
        val type = object : TypeToken<Map<String, String>>() {}.type

        // First, validate the JSON
        try {
            gson.fromJson<Map<String, String>>(jsonString, type)
        } catch (e: JsonSyntaxException) {
            Log.e("AppMapper", "Invalid JSON syntax, not saving.", e)
            return false
        }

        // If valid, write to the external file
        return try {
            val externalFile = File(context.getExternalFilesDir(null), CONFIG_FILE_NAME)
            externalFile.writeText(jsonString)
            // Reload the map in memory
            reload()
            true
        } catch (e: Exception) {
            Log.e("AppMapper", "Failed to write app map to external file", e)
            false
        }
    }

    fun getPackageName(appName: String): String? {
        // 1. Exact match
        appMap[appName]?.let { return it }
        
        // 2. Case insensitive match
        appMap.entries.find { it.key.equals(appName, ignoreCase = true) }?.let { return it.value }
        
        // 3. Partial match (optional, but risky if names are short)
        // appMap.entries.find { it.key.contains(appName, ignoreCase = true) }?.let { return it.value }
        
        return null 
    }
}
