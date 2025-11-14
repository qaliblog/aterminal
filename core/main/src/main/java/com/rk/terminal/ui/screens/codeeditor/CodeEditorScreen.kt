package com.rk.terminal.ui.screens.codeeditor

import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rk.libcommons.alpineDir
import com.rk.terminal.ui.activities.terminal.MainActivity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    val context = LocalContext.current
    var currentFile by remember { mutableStateOf<File?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var isModified by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Toolbar
        TopAppBar(
            title = { 
                Text(
                    text = currentFile?.name ?: "No file open",
                    maxLines = 1
                )
            },
            navigationIcon = {
                IconButton(onClick = { showFilePicker = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Open File")
                }
            },
            actions = {
                if (currentFile != null) {
                    IconButton(
                        onClick = {
                            if (isModified) {
                                saveFile(currentFile!!, fileContent)
                                isModified = false
                            }
                        },
                        enabled = isModified
                    ) {
                        Icon(Icons.Default.Done, contentDescription = "Save")
                    }
                    
                    IconButton(
                        onClick = {
                            currentFile = null
                            fileContent = ""
                            isModified = false
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
        )
        
        // Code Editor using EditText with monospace font
        AndroidView(
            factory = { ctx ->
                EditText(ctx).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(16, 16, 16, 16)
                    textSize = 14f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setHorizontallyScrolling(true)
                    isVerticalScrollBarEnabled = true
                    isHorizontalScrollBarEnabled = true
                    
                    // Listen for text changes
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) {
                            fileContent = s?.toString() ?: ""
                            isModified = true
                        }
                    })
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surface),
            update = { editText ->
                // Update content when file changes
                if (currentFile != null && editText.text.toString() != fileContent) {
                    editText.setText(fileContent)
                    editText.setSelection(fileContent.length)
                    isModified = false
                }
            }
        )
    }
    
    // File picker dialog - simplified version
    if (showFilePicker) {
        FilePickerDialog(
            initialPath = alpineDir().absolutePath,
            onDismiss = { showFilePicker = false },
            onFileSelected = { file ->
                try {
                    currentFile = file
                    fileContent = file.readText()
                    isModified = false
                    showFilePicker = false
                } catch (e: Exception) {
                    android.util.Log.e("CodeEditor", "Error reading file", e)
                }
            }
        )
    }
}

@Composable
fun FilePickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onFileSelected: (File) -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    
    LaunchedEffect(currentPath) {
        val dir = File(currentPath)
        files = if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open File") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                // Path bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (currentPath != initialPath) {
                        TextButton(onClick = {
                            File(currentPath).parentFile?.let {
                                currentPath = it.absolutePath
                            }
                        }) {
                            Text("←")
                        }
                    }
                    Text(
                        text = currentPath,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                }
                
                Divider()
                
                // File list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) { file ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            onClick = {
                                if (file.isDirectory) {
                                    currentPath = file.absolutePath
                                } else {
                                    onFileSelected(file)
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Default.CreateNewFolder else Icons.Default.InsertDriveFile,
                                    contentDescription = null
                                )
                                Text(file.name)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun saveFile(file: File, content: String) {
    try {
        file.writeText(content)
        android.util.Log.d("CodeEditor", "File saved: ${file.absolutePath}")
    } catch (e: Exception) {
        android.util.Log.e("CodeEditor", "Error saving file", e)
    }
}
