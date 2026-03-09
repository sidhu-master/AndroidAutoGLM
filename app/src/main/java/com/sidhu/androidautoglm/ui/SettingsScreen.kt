package com.sidhu.androidautoglm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.sidhu.androidautoglm.BuildConfig
import com.sidhu.androidautoglm.utils.ShizukuHelper
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.sidhu.androidautoglm.network.UpdateInfo
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
    onBatteryOptimizationToggle: (Boolean) -> Unit,
    onSave: (String, String, Boolean, String) -> Unit,
    onBack: () -> Unit,
    onOpenDocumentation: () -> Unit,
    onOpenUrl: (String) -> Unit,
    isWakeWordEnabled: Boolean,
    onWakeWordToggle: (Boolean) -> Unit,
    wakeWord: String,
    onWakeWordChange: (String) -> Unit,
    // 主/子模型配置
    masterApiKey: String = "",
    masterBaseUrl: String = "https://api.minimaxi.com/v1",
    masterIsGemini: Boolean = false,
    masterModelName: String = "MiniMax-M2.5",
    subUseMasterConfig: Boolean = false,
    subApiKey: String = "",
    subBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    subIsGemini: Boolean = false,
    subModelName: String = "autoglm-phone",
    onSaveFull: ((String, String, Boolean, String, Boolean, String, String, Boolean, String) -> Unit)? = null
) {
    val isDefaultKey = apiKey == BuildConfig.DEFAULT_API_KEY && BuildConfig.DEFAULT_API_KEY.isNotEmpty()

    var isEditing by remember { mutableStateOf(apiKey.isEmpty() && masterApiKey.isEmpty()) }
    var newKey by remember { mutableStateOf(if (isDefaultKey) "" else apiKey) }
    var newBaseUrl by remember { mutableStateOf(baseUrl) }
    var newIsGemini by remember { mutableStateOf(isGemini) }
    var newModelName by remember { mutableStateOf(modelName) }
    var newMasterKey by remember { mutableStateOf(masterApiKey) }
    var newMasterBaseUrl by remember { mutableStateOf(masterBaseUrl) }
    var newMasterIsGemini by remember { mutableStateOf(masterIsGemini) }
    var newMasterModelName by remember { mutableStateOf(masterModelName) }
    var newSubUseMasterConfig by remember { mutableStateOf(subUseMasterConfig) }
    var newSubKey by remember { mutableStateOf(if (subUseMasterConfig) "" else subApiKey) }
    var newSubBaseUrl by remember { mutableStateOf(subBaseUrl) }
    var newSubIsGemini by remember { mutableStateOf(subIsGemini) }
    var newSubModelName by remember { mutableStateOf(subModelName) }
    
    val keyboardController = LocalSoftwareKeyboardController.current
    

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
                // View Mode: 仅显示主模型和子模型名称
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
                                text = stringResource(R.string.master_model_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = masterModelName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.sub_model_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (subUseMasterConfig) masterModelName else subModelName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = { 
                                isEditing = true 
                                newKey = if (isDefaultKey) "" else apiKey
                                newBaseUrl = baseUrl
                                newIsGemini = isGemini
                                newModelName = modelName
                                newMasterKey = masterApiKey
                                newMasterBaseUrl = masterBaseUrl
                                newMasterIsGemini = masterIsGemini
                                newMasterModelName = masterModelName
                                newSubUseMasterConfig = subUseMasterConfig
                                newSubKey = if (subUseMasterConfig) "" else subApiKey
                                newSubBaseUrl = subBaseUrl
                                newSubIsGemini = subIsGemini
                                newSubModelName = subModelName
                            },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.edit_api_key))
                        }
                    }
                }

                // Battery Optimization Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.battery_optimization_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.battery_optimization_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isBatteryOptimizationIgnored,
                                onCheckedChange = onBatteryOptimizationToggle
                            )
                        }
                    }
                }

                // Wake Word Card（仅应用保活开启时显示）
                if (isBatteryOptimizationIgnored) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.wake_word_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(R.string.wake_word_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isWakeWordEnabled,
                                    onCheckedChange = onWakeWordToggle
                                )
                            }

                            // 唤醒词输入框
                            if (isWakeWordEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = wakeWord,
                                    onValueChange = onWakeWordChange,
                                    label = { Text(stringResource(R.string.wake_word_label)) },
                                    placeholder = { Text("皮皮虾") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 内置输入法 Card
                val scope = rememberCoroutineScope()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.ime_enable_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = stringResource(R.string.ime_enable_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (!ShizukuHelper.isShizukuAvailable() || !ShizukuHelper.checkPermission(context)) {
                                    Toast.makeText(context, R.string.ime_enable_fail_shizuku, Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                scope.launch {
                                    val imeId = "${BuildConfig.APPLICATION_ID}/com.sidhu.androidautoglm.input.AdbIME"
                                    val ok = withContext(Dispatchers.IO) {
                                        ShizukuHelper.executeShellCommand("ime enable $imeId")
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            if (ok) R.string.ime_enable_success else R.string.ime_enable_fail_shizuku,
                                            Toast.LENGTH_LONG
                                        ).show()
                                        if (ok) {
                                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.ime_enable_btn))
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
                        Text(
                            text = stringResource(R.string.master_model_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.master_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var masterTypeExpanded by remember { mutableStateOf(false) }
                        val isMasterDoubao = newMasterBaseUrl.contains("volces.com", ignoreCase = true)
                        val masterTypeLabel = when {
                            newMasterIsGemini -> stringResource(R.string.api_type_gemini)
                            isMasterDoubao -> stringResource(R.string.api_type_doubao_title)
                            else -> stringResource(R.string.api_type_openai_title)
                        }
                        ExposedDropdownMenuBox(
                            expanded = masterTypeExpanded,
                            onExpandedChange = { masterTypeExpanded = !masterTypeExpanded }
                        ) {
                            OutlinedTextField(
                                value = masterTypeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.api_type_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = masterTypeExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = masterTypeExpanded,
                                onDismissRequest = { masterTypeExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.api_type_openai_title)) },
                                    onClick = {
                                        newMasterIsGemini = false
                                        newMasterBaseUrl = "https://api.minimaxi.com/v1"
                                        newMasterModelName = "MiniMax-M2.5"
                                        masterTypeExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.api_type_doubao_title)) },
                                    onClick = {
                                        newMasterIsGemini = false
                                        newMasterBaseUrl = "https://ark.cn-beijing.volces.com/api/v3"
                                        newMasterModelName = ""
                                        masterTypeExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.api_type_gemini)) },
                                    onClick = {
                                        newMasterIsGemini = true
                                        newMasterBaseUrl = "https://generativelanguage.googleapis.com"
                                        newMasterModelName = "gemini-2.0-flash-exp"
                                        masterTypeExpanded = false
                                    }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = newMasterBaseUrl,
                            onValueChange = { newMasterBaseUrl = it },
                            label = { Text(stringResource(R.string.enter_base_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://api.minimaxi.com/v1") }
                        )
                        OutlinedTextField(
                            value = newMasterModelName,
                            onValueChange = { newMasterModelName = it },
                            label = { Text(stringResource(R.string.enter_model_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("MiniMax-M2.5") }
                        )
                        OutlinedTextField(
                            value = newMasterKey,
                            onValueChange = { newMasterKey = it },
                            label = { Text(stringResource(R.string.enter_api_key)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text(stringResource(R.string.api_key_placeholder)) }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = stringResource(R.string.sub_model_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.sub_model_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = newSubUseMasterConfig,
                                onCheckedChange = { newSubUseMasterConfig = it }
                            )
                            Text(
                                text = stringResource(R.string.sub_use_master_config),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.clickable { newSubUseMasterConfig = !newSubUseMasterConfig }
                            )
                        }

                        if (!newSubUseMasterConfig) {
                            var subTypeExpanded by remember { mutableStateOf(false) }
                            val isSubDoubao = newSubBaseUrl.contains("volces.com", ignoreCase = true)
                            val subTypeLabel = when {
                                newSubIsGemini -> stringResource(R.string.api_type_gemini)
                                isSubDoubao -> stringResource(R.string.api_type_doubao_title)
                                else -> stringResource(R.string.api_type_openai_title)
                            }
                            ExposedDropdownMenuBox(
                                expanded = subTypeExpanded,
                                onExpandedChange = { subTypeExpanded = !subTypeExpanded }
                            ) {
                                OutlinedTextField(
                                    value = subTypeLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text(stringResource(R.string.api_type_label)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = subTypeExpanded) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor()
                                )
                                ExposedDropdownMenu(
                                    expanded = subTypeExpanded,
                                    onDismissRequest = { subTypeExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.api_type_openai_title)) },
                                        onClick = {
                                            newSubIsGemini = false
                                            newSubBaseUrl = "https://open.bigmodel.cn/api/paas/v4"
                                            newSubModelName = "autoglm-phone"
                                            subTypeExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.api_type_doubao_title)) },
                                        onClick = {
                                            newSubIsGemini = false
                                            newSubBaseUrl = "https://ark.cn-beijing.volces.com/api/v3"
                                            newSubModelName = ""
                                            subTypeExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.api_type_gemini)) },
                                        onClick = {
                                            newSubIsGemini = true
                                            newSubBaseUrl = "https://generativelanguage.googleapis.com"
                                            newSubModelName = "gemini-2.0-flash-exp"
                                            subTypeExpanded = false
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = newSubBaseUrl,
                                onValueChange = { newSubBaseUrl = it },
                                label = { Text(stringResource(R.string.enter_base_url)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.base_url_placeholder)) }
                            )
                            OutlinedTextField(
                                value = newSubModelName,
                                onValueChange = { newSubModelName = it },
                                label = { Text(stringResource(R.string.enter_model_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.model_name_placeholder)) }
                            )
                            OutlinedTextField(
                                value = newSubKey,
                                onValueChange = { newSubKey = it },
                                label = { Text(stringResource(R.string.enter_api_key)) },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text(stringResource(R.string.api_key_placeholder)) }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(onClick = {
                                isEditing = false
                                newKey = if (isDefaultKey) "" else apiKey
                                newBaseUrl = baseUrl
                                newIsGemini = isGemini
                                newModelName = modelName
                                newMasterKey = masterApiKey
                                newMasterBaseUrl = masterBaseUrl
                                newMasterIsGemini = masterIsGemini
                                newMasterModelName = masterModelName
                                newSubUseMasterConfig = subUseMasterConfig
                                newSubKey = subApiKey
                                newSubBaseUrl = subBaseUrl
                                newSubIsGemini = subIsGemini
                                newSubModelName = subModelName
                                if (apiKey.isEmpty() && masterApiKey.isEmpty()) onBack()
                            }) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { 
                                    if (onSaveFull != null) {
                                        val subKey = if (newSubUseMasterConfig) newMasterKey else newSubKey
                                        val subBase = if (newSubUseMasterConfig) newMasterBaseUrl else newSubBaseUrl
                                        val subGemini = if (newSubUseMasterConfig) newMasterIsGemini else newSubIsGemini
                                        val subModel = if (newSubUseMasterConfig) newMasterModelName else newSubModelName
                                        onSaveFull(newMasterKey, newMasterBaseUrl, newMasterIsGemini, newMasterModelName,
                                            newSubUseMasterConfig, subKey, subBase, subGemini, subModel)
                                    } else {
                                        val subKey = if (newSubUseMasterConfig) newMasterKey else newSubKey
                                        val subBase = if (newSubUseMasterConfig) newMasterBaseUrl else newSubBaseUrl
                                        val subGemini = if (newSubUseMasterConfig) newMasterIsGemini else newSubIsGemini
                                        val subModel = if (newSubUseMasterConfig) newMasterModelName else newSubModelName
                                        onSave(subKey, subBase, subGemini, subModel)
                                    }
                                    onBack() 
                                },
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
        onBatteryOptimizationToggle = {},
        onSave = { _, _, _, _ -> },
        onBack = {},
        onOpenDocumentation = {},
        onOpenUrl = {},
        isWakeWordEnabled = false,
        onWakeWordToggle = {},
        wakeWord = "皮皮虾",
        onWakeWordChange = {},
        masterApiKey = "",
        masterBaseUrl = "https://api.minimaxi.com/v1",
        masterIsGemini = false,
        masterModelName = "MiniMax-M2.5",
        subUseMasterConfig = false,
        subApiKey = "",
        subBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        subIsGemini = false,
        subModelName = "autoglm-phone",
        onSaveFull = null
    )
}
