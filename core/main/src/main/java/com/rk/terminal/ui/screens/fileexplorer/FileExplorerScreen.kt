package com.rk.terminal.ui.screens.fileexplorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.libcommons.alpineDir
import com.rk.terminal.ui.activities.terminal.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    val context = LocalContext.current
    // Start at Alpine root directory
    val initialPath = alpineDir().absolutePath
    var currentPath by remember { mutableStateOf(initialPath) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<FileItem?>(null) }
    var selectedFiles by remember { mutableStateOf<Set<File>>(emptySet()) }
    var clipboardFiles by remember { mutableStateOf<List<Pair<File, String>>?>(null) } // Pair<File, operation> where operation is "copy" or "cut"
    var showNewFileDialog by remember { mutableStateOf(false) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var isSelectionMode by remember { mutableStateOf(false) }
    
    // Load files when path changes
    LaunchedEffect(currentPath) {
        isLoading = true
        files = loadFiles(currentPath)
        isLoading = false
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Top bar with actions
        TopAppBar(
            title = { 
                Text("File Explorer")
            },
            navigationIcon = {
                if (currentPath != initialPath) {
                    IconButton(onClick = {
                        val parent = File(currentPath).parentFile
                        if (parent != null) {
                            currentPath = parent.absolutePath
                            selectedFiles = emptySet()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                    }
                }
            },
            actions = {
                if (isSelectionMode) {
                    // Selection mode actions
                    if (selectedFiles.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboardFiles = selectedFiles.map { it to "copy" }
                            selectedFiles = emptySet()
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = {
                            clipboardFiles = selectedFiles.map { it to "cut" }
                            selectedFiles = emptySet()
                            isSelectionMode = false
                        }) {
                            Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                        }
                        IconButton(onClick = {
                            showDeleteDialog = FileItem(
                                file = selectedFiles.first(),
                                name = if (selectedFiles.size == 1) selectedFiles.first().name else "${selectedFiles.size} items",
                                isDirectory = selectedFiles.first().isDirectory,
                                isFile = selectedFiles.first().isFile,
                                icon = Icons.Default.Delete,
                                iconColor = Color.Red,
                                details = "",
                                size = ""
                            )
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    IconButton(onClick = {
                        selectedFiles = emptySet()
                        isSelectionMode = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                    }
                } else {
                    // Normal mode actions
                    if (clipboardFiles != null && clipboardFiles!!.isNotEmpty()) {
                        IconButton(onClick = {
                            // Paste operation
                            clipboardFiles?.forEach { (file, operation) ->
                                var destFile = File(currentPath, file.name)
                                // Handle duplicate names
                                var counter = 1
                                while (destFile.exists()) {
                                    val nameWithoutExt = file.name.substringBeforeLast(".")
                                    val ext = if (file.name.contains(".")) ".${file.name.substringAfterLast(".")}" else ""
                                    val newName = if (file.isDirectory) {
                                        "${file.name}_$counter"
                                    } else {
                                        "${nameWithoutExt}_$counter$ext"
                                    }
                                    destFile = File(currentPath, newName)
                                    counter++
                                }
                                
                                if (operation == "cut") {
                                    file.renameTo(destFile)
                                } else {
                                    copyFile(file, destFile)
                                }
                            }
                            clipboardFiles = null
                            // Reload files
                            val reloadPath = currentPath
                            currentPath = ""
                            currentPath = reloadPath
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    }
                    IconButton(onClick = { showNewFileDialog = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = "New File")
                    }
                    IconButton(onClick = { showNewDirDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Directory")
                    }
                    IconButton(onClick = { isSelectionMode = true }) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Select")
                    }
                }
            }
        )
        
        // Path bar with navigation
        PathBar(
            currentPath = currentPath,
            initialPath = initialPath,
            onNavigateUp = {
                val parent = File(currentPath).parentFile
                if (parent != null && currentPath != initialPath) {
                    currentPath = parent.absolutePath
                    selectedFiles = emptySet()
                }
            },
            onPathClick = { path ->
                currentPath = path
                selectedFiles = emptySet()
            },
            modifier = Modifier.fillMaxWidth()
        )
        
        // File list
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            FileList(
                files = files,
                selectedFiles = selectedFiles,
                isSelectionMode = isSelectionMode,
                onFileClick = { fileItem ->
                    if (isSelectionMode) {
                        // Toggle selection
                        selectedFiles = if (selectedFiles.contains(fileItem.file)) {
                            selectedFiles - fileItem.file
                        } else {
                            selectedFiles + fileItem.file
                        }
                    } else {
                        when {
                            fileItem.isDirectory -> {
                                currentPath = fileItem.file.absolutePath
                            }
                            fileItem.isFile -> {
                                // Open file in text editor or appropriate app
                                openFile(context, fileItem.file)
                            }
                        }
                    }
                },
                onFileLongPress = { fileItem ->
                    if (!isSelectionMode) {
                        isSelectionMode = true
                        selectedFiles = setOf(fileItem.file)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            )
        }
    }
    
    // Delete confirmation dialog
    showDeleteDialog?.let { fileItem ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete ${if (fileItem.isDirectory) "Folder" else "File"}?") },
            text = { 
                Text("Are you sure you want to delete \"${fileItem.name}\"?${if (selectedFiles.size > 1) "\n\nThis will delete ${selectedFiles.size} items." else ""}") 
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedFiles.isNotEmpty()) {
                            // Delete all selected files
                            selectedFiles.forEach { file ->
                                deleteFile(file)
                            }
                            selectedFiles = emptySet()
                            isSelectionMode = false
                        } else {
                            deleteFile(fileItem.file)
                        }
                        // Trigger reload
                        val reloadPath = currentPath
                        currentPath = ""
                        currentPath = reloadPath
                        showDeleteDialog = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // New file dialog
    if (showNewFileDialog) {
        var fileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("New File") },
            text = {
                Column {
                    Text("Enter file name:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            val newFile = File(currentPath, fileName)
                            newFile.createNewFile()
                            showNewFileDialog = false
                            // Reload files
                            val reloadPath = currentPath
                            currentPath = ""
                            currentPath = reloadPath
                        }
                    },
                    enabled = fileName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // New directory dialog
    if (showNewDirDialog) {
        var dirName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDirDialog = false },
            title = { Text("New Directory") },
            text = {
                Column {
                    Text("Enter directory name:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dirName,
                        onValueChange = { dirName = it },
                        label = { Text("Directory name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dirName.isNotBlank()) {
                            val newDir = File(currentPath, dirName)
                            newDir.mkdirs()
                            showNewDirDialog = false
                            // Reload files
                            val reloadPath = currentPath
                            currentPath = ""
                            currentPath = reloadPath
                        }
                    },
                    enabled = dirName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDirDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PathBar(
    currentPath: String,
    initialPath: String,
    onNavigateUp: () -> Unit,
    onPathClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pathParts = currentPath.split(File.separator).filter { it.isNotEmpty() }
    val initialParts = initialPath.split(File.separator).filter { it.isNotEmpty() }
    
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (currentPath != initialPath) {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Up")
                }
            }
            
            if (pathParts.isNotEmpty()) {
                TextButton(onClick = { onPathClick(initialPath) }) {
                    Text("Root", fontSize = 12.sp)
                }
                
                pathParts.drop(initialParts.size).forEachIndexed { index, part ->
                    Text("/", fontSize = 12.sp)
                    TextButton(
                        onClick = {
                            val targetPath = initialParts + pathParts.take(initialParts.size + index + 1)
                            onPathClick(targetPath.joinToString(File.separator, prefix = File.separator))
                        }
                    ) {
                        Text(part, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun FileList(
    files: List<FileItem>,
    selectedFiles: Set<File>,
    isSelectionMode: Boolean,
    onFileClick: (FileItem) -> Unit,
    onFileLongPress: (FileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(files) { fileItem ->
            FileItemRow(
                fileItem = fileItem,
                isSelected = selectedFiles.contains(fileItem.file),
                isSelectionMode = isSelectionMode,
                onClick = { onFileClick(fileItem) },
                onLongClick = { onFileLongPress(fileItem) }
            )
        }
    }
}

@Composable
fun FileItemRow(
    fileItem: FileItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = fileItem.icon,
                contentDescription = null,
                tint = fileItem.iconColor,
                modifier = Modifier.size(32.dp)
            )
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = fileItem.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isSelectionMode && isSelected) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            if (fileItem.isFile) {
                Text(
                    text = fileItem.size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val icon: ImageVector,
    val iconColor: Color,
    val details: String,
    val size: String = ""
)

fun loadFiles(path: String): List<FileItem> {
    val directory = File(path)
    if (!directory.exists() || !directory.isDirectory) {
        return emptyList()
    }
    
    val files = directory.listFiles()?.toList() ?: emptyList()
    
    return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .map { file ->
            val isDirectory = file.isDirectory
            val isFile = file.isFile
            
            val iconPair: Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> = when {
                isDirectory -> Pair(Icons.Default.Folder, androidx.compose.ui.graphics.Color(0xFF2196F3))
                file.name.endsWith(".txt", ignoreCase = true) ||
                file.name.endsWith(".md", ignoreCase = true) ||
                file.name.endsWith(".log", ignoreCase = true) -> 
                    Pair(Icons.Default.Description, androidx.compose.ui.graphics.Color(0xFF757575))
                file.name.endsWith(".jpg", ignoreCase = true) ||
                file.name.endsWith(".png", ignoreCase = true) ||
                file.name.endsWith(".gif", ignoreCase = true) ||
                file.name.endsWith(".webp", ignoreCase = true) -> 
                    Pair(Icons.Default.Image, androidx.compose.ui.graphics.Color(0xFF2196F3))
                file.name.endsWith(".zip", ignoreCase = true) ||
                file.name.endsWith(".tar", ignoreCase = true) ||
                file.name.endsWith(".gz", ignoreCase = true) -> 
                    Pair(Icons.Default.Archive, androidx.compose.ui.graphics.Color(0xFF9C27B0))
                else -> Pair(Icons.Default.InsertDriveFile, androidx.compose.ui.graphics.Color(0xFF757575))
            }
            val icon = iconPair.first
            val iconColor = iconPair.second
            
            val details = if (isDirectory) {
                "${file.listFiles()?.size ?: 0} items"
            } else {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                dateFormat.format(Date(file.lastModified()))
            }
            
            val size = if (isFile) {
                formatFileSize(file.length())
            } else {
                ""
            }
            
            FileItem(
                file = file,
                name = file.name,
                isDirectory = isDirectory,
                isFile = isFile,
                icon = icon,
                iconColor = iconColor,
                details = details,
                size = size
            )
        }
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

fun deleteFile(file: File): Boolean {
    return if (file.isDirectory) {
        file.deleteRecursively()
    } else {
        file.delete()
    }
}

fun openFile(context: android.content.Context, file: File) {
    // This will be handled by the text editor or appropriate app
    // For now, we'll just log it
    android.util.Log.d("FileExplorer", "Opening file: ${file.absolutePath}")
}

fun copyFile(source: File, dest: File): Boolean {
    return try {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles()?.forEach { child ->
                copyFile(child, File(dest, child.name))
            }
            true
        } else {
            source.copyTo(dest, overwrite = true)
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("FileExplorer", "Error copying file", e)
        false
    }
}
