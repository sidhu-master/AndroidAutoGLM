package com.sidhu.androidautoglm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sidhu.androidautoglm.R
import java.io.BufferedReader
import java.io.InputStreamReader

import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownViewerScreen(
    initialLanguage: String = "zh",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentLanguage by remember { mutableStateOf(initialLanguage) }
    var markdownContent by remember { mutableStateOf("") }
    var textOnlyContent by remember { mutableStateOf("") }
    var showImages by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var isWebLoading by remember { mutableStateOf(true) }
    var showLanguageMenu by remember { mutableStateOf(false) }

    LaunchedEffect(currentLanguage) {
        val fileName = if (currentLanguage == "zh") "README.md" else "README_EN.md"
        isLoading = true
        val content = withContext(Dispatchers.IO) {
            loadMarkdownFromAssets(context, fileName)
        }
        markdownContent = content
        textOnlyContent = stripImages(content)
        showImages = false
        isLoading = false
        isWebLoading = true
    }

    LaunchedEffect(markdownContent) {
        if (markdownContent.isBlank()) return@LaunchedEffect
        delay(800)
        isWebLoading = true
        showImages = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.documentation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showLanguageMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = stringResource(R.string.switch_language_cd)
                        )
                    }
                    DropdownMenu(
                        expanded = showLanguageMenu,
                        onDismissRequest = { showLanguageMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.language_chinese_option)) },
                            onClick = {
                                currentLanguage = "zh"
                                showLanguageMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.language_english_option)) },
                            onClick = {
                                currentLanguage = "en"
                                showLanguageMenu = false
                            }
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
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                MarkdownWebView(
                    markdown = if (showImages) markdownContent else textOnlyContent,
                    modifier = Modifier.fillMaxSize(),
                    onPageStarted = { isWebLoading = true },
                    onPageFinished = { isWebLoading = false }
                )
                    if (isWebLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownWebView(
    markdown: String,
    modifier: Modifier = Modifier,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit
) {
    val context = LocalContext.current

    AndroidView(
        factory = {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        onPageStarted()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageFinished()
                    }
                }

                val htmlContent = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                        <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                        <style>
                            body {
                                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                padding: 16px;
                                line-height: 1.6;
                                color: #333;
                                background-color: transparent;
                            }
                            img {
                                max-width: 100%;
                                height: auto;
                                border-radius: 8px;
                            }
                            h1, h2, h3 {
                                color: #1a1a1a;
                            }
                            code {
                                background-color: #f4f4f4;
                                padding: 2px 6px;
                                border-radius: 4px;
                            }
                            pre {
                                background-color: #f4f4f4;
                                padding: 12px;
                                border-radius: 8px;
                                overflow-x: auto;
                            }
                            ul, ol {
                                padding-left: 24px;
                            }
                            li {
                                margin-bottom: 8px;
                            }
                            a {
                                color: #0066cc;
                            }
                        </style>
                    </head>
                    <body>
                        <div id="content"></div>
                        <script>
                            document.getElementById('content').innerHTML = marked.parse(`${
                                markdown
                                    .replace("\\", "\\\\")
                                    .replace("`", "\\`")
                                    .replace("$", "\\$")
                                    .replace("\n", "\\n")
                                    .replace("\r", "")
                            }`);
                        </script>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL(
                    "file:///android_asset/",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        update = { webView ->
            val htmlContent = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <style>
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                            padding: 16px;
                            line-height: 1.6;
                            color: #333;
                            background-color: transparent;
                        }
                        img {
                            max-width: 100%;
                            height: auto;
                            border-radius: 8px;
                        }
                        h1, h2, h3 {
                            color: #1a1a1a;
                        }
                        code {
                            background-color: #f4f4f4;
                            padding: 2px 6px;
                            border-radius: 4px;
                        }
                        pre {
                            background-color: #f4f4f4;
                            padding: 12px;
                            border-radius: 8px;
                            overflow-x: auto;
                        }
                        ul, ol {
                            padding-left: 24px;
                        }
                        li {
                            margin-bottom: 8px;
                        }
                        a {
                            color: #0066cc;
                        }
                    </style>
                </head>
                <body>
                    <div id="content"></div>
                    <script>
                        document.getElementById('content').innerHTML = marked.parse(`${
                            markdown
                                .replace("\\", "\\\\")
                                .replace("`", "\\`")
                                .replace("$", "\\$")
                                .replace("\n", "\\n")
                                .replace("\r", "")
                        }`);
                    </script>
                </body>
                </html>
            """.trimIndent()

            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier
    )
}

private fun loadMarkdownFromAssets(context: Context, fileName: String): String {
    return try {
        val inputStream = context.assets.open(fileName)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val content = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            content.append(line).append("\n")
        }
        reader.close()
        content.toString()
    } catch (e: Exception) {
        "Error loading documentation: ${e.message}"
    }
}

private fun stripImages(markdown: String): String {
    return markdown
        .replace(Regex("!\\[[^\\]]*]\\([^\\)]*\\)"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
}
