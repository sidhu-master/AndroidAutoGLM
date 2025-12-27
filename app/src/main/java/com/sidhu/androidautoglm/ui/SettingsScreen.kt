package com.sidhu.androidautoglm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.sidhu.androidautoglm.network.UpdateInfo
import com.sidhu.androidautoglm.BuildConfig
import com.sidhu.androidautoglm.R

// Updated by AI: Settings Screen with i18n support
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    apiKey: String,
    baseUrl: String,
    isGemini: Boolean,
    modelName: String,
    appUpdateInfo: UpdateInfo?,
    currentLanguage: String,
    onLanguageChange: (String) -> Unit,
    isBatteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onSave: (String, String, Boolean, String) -> Unit,
    onBack: () -> Unit,
    onOpenDocumentation: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val isDefaultKey = apiKey == BuildConfig.DEFAULT_API_KEY && BuildConfig.DEFAULT_API_KEY.isNotEmpty()

    // If we have an existing key, start in "View Mode" (not editing), otherwise "Edit Mode"
    var isEditing by remember { mutableStateOf(apiKey.isEmpty()) }
    // The key being typed in Edit Mode. If default key, start empty to avoid revealing it.
    var newKey by remember { mutableStateOf(if (isDefaultKey) "" else apiKey) }
    var newBaseUrl by remember { mutableStateOf(baseUrl) }
    var newIsGemini by remember { mutableStateOf(isGemini) }
    var newModelName by remember { mutableStateOf(modelName) }
    
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Visibility toggle only for the input field in Edit Mode
    var isInputVisible by remember { mutableStateOf(false) }

    // Update Logic
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        onLanguageChange(if (currentLanguage == "zh") "en" else "zh")
                    }) {
                        Text(
                            text = if (currentLanguage == "zh") "English" else "中文",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isEditing) {
                // View Mode: Show masked key + Edit button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.api_key_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (isDefaultKey) stringResource(R.string.api_key_default_masked) else getMaskedKey(apiKey),
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.model_name_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = modelName,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.api_type_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isGemini) stringResource(R.string.api_type_gemini) else stringResource(R.string.api_type_openai),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.base_url_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = baseUrl,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Button(
                            onClick = { 
                                isEditing = true 
                                newKey = if (isDefaultKey) "" else apiKey
                                newBaseUrl = baseUrl
                                newIsGemini = isGemini
                                newModelName = modelName
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.edit_api_key))
                        }
                    }
                }

                // Battery Optimization Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRequestBatteryOptimization),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.battery_optimization_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            Text(
                                text = if (isBatteryOptimizationIgnored) 
                                    stringResource(R.string.battery_optimization_desc_on) 
                                else 
                                    stringResource(R.string.battery_optimization_desc_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isBatteryOptimizationIgnored) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (!isBatteryOptimizationIgnored) {
                                Button(
                                    onClick = onRequestBatteryOptimization,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Text(stringResource(R.string.battery_optimization_allow))
                                }
                            }
                        }
                        
                        if (isBatteryOptimizationIgnored) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Version Info Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = appUpdateInfo != null) {
                                if (appUpdateInfo != null) {
                                    val url = if (!appUpdateInfo.releasePage.isNullOrEmpty()) {
                                        appUpdateInfo.releasePage
                                    } else {
                                        appUpdateInfo.downloadUrl
                                    }
                                    onOpenUrl(url)
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.version_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Text(
                            text = BuildConfig.VERSION_NAME,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        if (appUpdateInfo != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(containerColor = Color.Red) {
                                Text(stringResource(R.string.new_version_badge), color = Color.White)
                            }
                        }
                    }
                }
            } else {
                // Edit Mode: Input field
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings_title), // Changed label since we moved API Key
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 1. API Type Selection (Dropdown)
                        var typeExpanded by remember { mutableStateOf(false) }
                        val isDoubao = newBaseUrl.contains("volces.com", ignoreCase = true)
                        val currentTypeLabel = if (newIsGemini) {
                            stringResource(R.string.api_type_gemini)
                        } else if (isDoubao) {
                            stringResource(R.string.api_type_doubao_title)
                        } else {
                            stringResource(R.string.api_type_openai_title)
                        }

                        ExposedDropdownMenuBox(
                            expanded = typeExpanded,
                            onExpandedChange = { typeExpanded = !typeExpanded }
                        ) {
                            OutlinedTextField(
                                value = currentTypeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.api_type_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = typeExpanded,
                                onDismissRequest = { typeExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = stringResource(R.string.api_type_openai_title),
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.api_type_openai_desc),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        newIsGemini = false
                                        newBaseUrl = "https://open.bigmodel.cn/api/paas/v4"
                                        newModelName = "autoglm-phone"
                                        typeExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = stringResource(R.string.api_type_doubao_title),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    onClick = {
                                        newIsGemini = false
                                        newBaseUrl = "https://ark.cn-beijing.volces.com/api/v3"
                                        newModelName = "" // Force user to enter EP
                                        typeExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.api_type_gemini)) },
                                    onClick = {
                                        newIsGemini = true
                                        newBaseUrl = "https://generativelanguage.googleapis.com"
                                        newModelName = "gemini-2.0-flash-exp"
                                        typeExpanded = false
                                    }
                                )
                            }
                        }

                        // 2. Base URL Input
                        OutlinedTextField(
                            value = newBaseUrl,
                            onValueChange = { 
                                newBaseUrl = it
                                // Optional: Auto-detect if user manually types a google url
                                if (it.contains("googleapis.com")) newIsGemini = true
                            },
                            label = { Text(stringResource(R.string.enter_base_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            placeholder = { Text(stringResource(R.string.base_url_placeholder)) },
                            supportingText = {
                                Text(
                                    text = if (newIsGemini) stringResource(R.string.base_url_hint_gemini) 
                                           else if (isDoubao) stringResource(R.string.base_url_hint_doubao)
                                           else stringResource(R.string.base_url_hint_openai),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        // 3. Model Name Input
                        if (newIsGemini) {
                            var modelExpanded by remember { mutableStateOf(false) }
                            val geminiModels = listOf(
                                "gemini-2.0-flash-exp",
                                "gemini-2.5-flash-lite",
                                "gemini-3-flash-preview",
                                "gemini-3-pro-preview",
                                "gemini-1.5-flash",
                                "gemini-1.5-pro"
                            )

                            ExposedDropdownMenuBox(
                                expanded = modelExpanded,
                                onExpandedChange = { modelExpanded = !modelExpanded }
                            ) {
                                OutlinedTextField(
                                    value = newModelName,
                                    onValueChange = { newModelName = it },
                                    label = { Text(stringResource(R.string.enter_model_name)) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = { keyboardController?.hide() }
                                    )
                                )

                                ExposedDropdownMenu(
                                    expanded = modelExpanded,
                                    onDismissRequest = { modelExpanded = false }
                                ) {
                                    geminiModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                newModelName = model
                                                modelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else if (isDoubao) {
                            var modelExpanded by remember { mutableStateOf(false) }
                            // Common Doubao models (Proxies) or Hints for Official API
                            val doubaoModels = listOf(
                                "doubao-seed-1-6-flash-250828",
                                "doubao-seed-1-6-lite-251015",
                                "doubao-seed-1-6-251015",
                                "doubao-seed-1-8-251215"
                            )

                            ExposedDropdownMenuBox(
                                expanded = modelExpanded,
                                onExpandedChange = { modelExpanded = !modelExpanded }
                            ) {
                                OutlinedTextField(
                                    value = newModelName,
                                    onValueChange = { newModelName = it },
                                    label = { Text(stringResource(R.string.enter_model_name)) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = { keyboardController?.hide() }
                                    ),
                                    placeholder = { Text(stringResource(R.string.model_name_placeholder_doubao)) }
                                )

                                ExposedDropdownMenu(
                                    expanded = modelExpanded,
                                    onDismissRequest = { modelExpanded = false }
                                ) {
                                    doubaoModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                newModelName = model
                                                modelExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = newModelName,
                                onValueChange = { newModelName = it },
                                label = { Text(stringResource(R.string.enter_model_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(
                                    onDone = { keyboardController?.hide() }
                                ),
                                placeholder = { 
                                    Text(
                                        if (isDoubao) stringResource(R.string.model_name_placeholder_doubao)
                                        else stringResource(R.string.model_name_placeholder) 
                                    ) 
                                }
                            )
                        }

                        // 4. API Key Input (Moved to bottom)
                        OutlinedTextField(
                            value = newKey,
                            onValueChange = { newKey = it },
                            label = { Text(stringResource(R.string.enter_api_key)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (isInputVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (isInputVisible)
                                    Icons.Filled.Visibility
                                else
                                    Icons.Filled.VisibilityOff

                                val description = if (isInputVisible) stringResource(R.string.hide_api_key) else stringResource(R.string.show_api_key)

                                IconButton(onClick = { isInputVisible = !isInputVisible }) {
                                    Icon(imageVector = image, contentDescription = description)
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            placeholder = { 
                                Text(
                                    if (isDefaultKey) stringResource(R.string.api_key_default_edit_placeholder) 
                                    else stringResource(R.string.api_key_placeholder)
                                ) 
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(onClick = {
                                if (apiKey.isNotEmpty()) {
                                    isEditing = false
                                    newKey = if (isDefaultKey) "" else apiKey
                                    newBaseUrl = baseUrl
                                    newIsGemini = isGemini
                                    newModelName = modelName
                                } else {
                                    onBack()
                                }
                            }) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { 
                                    // Allow saving empty key (to restore default) or valid key
                                    onSave(newKey, newBaseUrl, newIsGemini, newModelName)
                                    onBack() 
                                },
                                // Enable save button if key is not blank OR if user cleared it (to reset to default)
                                // Actually, if user clears it, we interpret it as reset to default.
                                enabled = true 
                            ) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }

            // Documentation Link
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                onClick = onOpenDocumentation
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.view_documentation),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun getMaskedKey(key: String): String {
    if (key.length <= 8) return "******"
    return "${key.take(4)}...${key.takeLast(4)}"
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    SettingsScreen(
        apiKey = "sk-...",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        isGemini = false,
        modelName = "autoglm-phone",
        appUpdateInfo = null,
        currentLanguage = "en",
        onLanguageChange = {},
        isBatteryOptimizationIgnored = false,
        onRequestBatteryOptimization = {},
        onSave = { _, _, _, _ -> },
        onBack = {},
        onOpenDocumentation = {},
        onOpenUrl = {}
    )
}
