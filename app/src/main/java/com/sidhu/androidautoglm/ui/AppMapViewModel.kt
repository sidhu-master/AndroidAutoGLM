package com.sidhu.androidautoglm.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.sidhu.androidautoglm.R
import com.sidhu.androidautoglm.action.AppMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap

data class AppMapUiState(
    val jsonText: String = "",
    val entries: List<MapEntry> = emptyList(),
    val error: String? = null,
    val isSaved: Boolean = false
)

data class MapEntry(
    val key: String,
    val value: String
)

class AppMapViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppMapUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadMap()
    }

    fun loadMap() {
        val json = AppMapper.getAppMapJson()
        val entries = parseEntries(json)
        _uiState.value = _uiState.value.copy(
            jsonText = json,
            entries = entries,
            error = null,
            isSaved = false
        )
    }

    private fun parseEntries(json: String): List<MapEntry> {
        return try {
            val gson = GsonBuilder().create()
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = gson.fromJson(json, type) ?: emptyMap()
            map.entries.map { MapEntry(it.key, it.value) }.sortedBy { it.key }
        } catch (e: Exception) {
            Log.e("AppMapViewModel", "Error parsing entries", e)
            emptyList()
        }
    }
    
    fun updateJsonText(text: String) {
        _uiState.value = _uiState.value.copy(jsonText = text, isSaved = false)
    }
    
    fun syncEntriesFromJson() {
        val entries = parseEntries(_uiState.value.jsonText)
        _uiState.value = _uiState.value.copy(entries = entries)
    }

    fun updateEntry(index: Int, key: String, value: String) {
        val currentEntries = _uiState.value.entries.toMutableList()
        if (index in currentEntries.indices) {
            currentEntries[index] = MapEntry(key, value)
            updateStateFromEntries(currentEntries)
        }
    }

    fun addEntry() {
        val currentEntries = _uiState.value.entries.toMutableList()
        currentEntries.add(0, MapEntry("", ""))
        updateStateFromEntries(currentEntries)
    }

    fun removeEntry(index: Int) {
        val currentEntries = _uiState.value.entries.toMutableList()
        if (index in currentEntries.indices) {
            currentEntries.removeAt(index)
            updateStateFromEntries(currentEntries)
        }
    }

    private fun updateStateFromEntries(entries: List<MapEntry>) {
        val map = entries.associate { it.key to it.value }
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(map)
        
        _uiState.value = _uiState.value.copy(
            entries = entries,
            jsonText = json,
            isSaved = false
        )
    }

    fun save() {
        var jsonToSave = _uiState.value.jsonText
        
        try {
            val gson = GsonBuilder().create()
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String>? = gson.fromJson(jsonToSave, type)
            
            if (map != null) {
                val filteredEntries = map.entries
                    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
                    .sortedBy { it.key }
                
                val newMap = LinkedHashMap<String, String>()
                filteredEntries.forEach { newMap[it.key] = it.value }
                
                val prettyGson = GsonBuilder().setPrettyPrinting().create()
                jsonToSave = prettyGson.toJson(newMap)
                
                _uiState.value = _uiState.value.copy(
                    jsonText = jsonToSave,
                    entries = filteredEntries.map { MapEntry(it.key, it.value) }
                )
            }
        } catch (e: Exception) {
            Log.w("AppMapViewModel", "Could not clean JSON, saving as-is. ${e.message}")
        }

        val success = AppMapper.saveAppMap(jsonToSave)
        if (success) {
            // After successful save, reload to ensure consistency
            loadMap()
            _uiState.value = _uiState.value.copy(isSaved = true)
        } else {
            val context = getApplication<Application>()
            _uiState.value = _uiState.value.copy(error = context.getString(R.string.error_invalid_json))
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
