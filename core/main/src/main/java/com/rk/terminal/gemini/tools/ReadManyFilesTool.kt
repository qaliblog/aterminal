package com.rk.terminal.gemini.tools

import com.rk.libcommons.alpineDir
import com.rk.terminal.gemini.core.FunctionDeclaration
import com.rk.terminal.gemini.core.FunctionParameters
import com.rk.terminal.gemini.core.PropertySchema
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths

data class ReadManyFilesParams(
    val include: List<String>,
    val exclude: List<String>? = null,
    val recursive: Boolean? = null,
    val useDefaultExcludes: Boolean? = true
)

class ReadManyFilesToolInvocation(
    private val params: ReadManyFilesParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<ReadManyFilesParams, ToolResult> {
    
    override val params: ReadManyFilesParams = params
    
    override fun getDescription(): String {
        val includeDesc = params.include.joinToString("`, `", prefix = "`", postfix = "`")
        val excludeDesc = if (params.exclude.isNullOrEmpty()) {
            "none specified"
        } else {
            params.exclude.take(2).joinToString("`, `", prefix = "`", postfix = "`") +
                if (params.exclude.size > 2) "..." else ""
        }
        return "Will attempt to read and concatenate files using patterns: $includeDesc. Excluding: $excludeDesc."
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList() // Multiple files, can't specify single location
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Read cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        val filesToRead = mutableListOf<File>()
        val skippedFiles = mutableListOf<Pair<String, String>>()
        
        // Collect files matching include patterns
        val includePatterns = params.include.map { pattern ->
            convertGlobToRegex(pattern)
        }
        
        val excludePatterns = (params.exclude ?: emptyList()).map { pattern ->
            convertGlobToRegex(pattern)
        }
        
        val rootDir = File(workspaceRoot)
        collectFiles(rootDir, includePatterns, excludePatterns, filesToRead, skippedFiles, signal)
        
        if (filesToRead.isEmpty()) {
            return ToolResult(
                llmContent = "No files found matching the include patterns.",
                returnDisplay = "No files found"
            )
        }
        
        // Read and concatenate files
        val contentParts = mutableListOf<String>()
        var successCount = 0
        var errorCount = 0
        
        for (file in filesToRead) {
            if (signal?.isAborted() == true) break
            
            try {
                val content = file.readText()
                val relativePath = file.relativeTo(rootDir).path
                contentParts.add("--- $relativePath ---")
                contentParts.add(content)
                contentParts.add("--- End of content ---")
                successCount++
            } catch (e: Exception) {
                skippedFiles.add(file.absolutePath to "Error reading: ${e.message}")
                errorCount++
            }
        }
        
        val result = contentParts.joinToString("\n\n")
        val summary = "Read $successCount file(s)." + 
            if (errorCount > 0) " $errorCount file(s) had errors." else ""
        
        updateOutput?.invoke(summary)
        
        return ToolResult(
            llmContent = result,
            returnDisplay = summary
        )
    }
    
    private fun collectFiles(
        dir: File,
        includePatterns: List<Regex>,
        excludePatterns: List<Regex>,
        filesToRead: MutableList<File>,
        skippedFiles: MutableList<Pair<String, String>>,
        signal: CancellationSignal?
    ) {
        if (signal?.isAborted() == true) return
        if (!dir.exists() || !dir.isDirectory) return
        
        dir.listFiles()?.forEach { file ->
            if (signal?.isAborted() == true) return
            
            val relativePath = file.relativeTo(File(workspaceRoot)).path
            
            // Check exclude patterns
            val isExcluded = excludePatterns.any { pattern ->
                pattern.matches(relativePath)
            }
            
            if (isExcluded) {
                skippedFiles.add(relativePath to "Excluded by pattern")
                return@forEach
            }
            
            if (file.isFile) {
                // Check include patterns
                val matches = includePatterns.any { pattern ->
                    pattern.matches(relativePath)
                }
                
                if (matches) {
                    filesToRead.add(file)
                }
            } else if (file.isDirectory) {
                // Recursively search subdirectories
                collectFiles(file, includePatterns, excludePatterns, filesToRead, skippedFiles, signal)
            }
        }
    }
    
    private fun convertGlobToRegex(glob: String): Regex {
        // Convert glob pattern to regex
        var regex = glob
            .replace("\\", "/") // Normalize separators
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        
        // Handle ** for recursive matching
        regex = regex.replace("**", ".*")
        
        return Regex("^$regex$")
    }
}

class ReadManyFilesTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<ReadManyFilesParams, ToolResult>() {
    
    override val name = "read_many_files"
    override val displayName = "ReadManyFiles"
    override val description = "Reads and concatenates multiple files matching glob patterns. Useful for reading multiple related files at once."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "include" to PropertySchema(
                type = "array",
                description = "Glob patterns for files to include. Example: [\"*.kt\", \"src/**/*.java\"]"
            ),
            "exclude" to PropertySchema(
                type = "array",
                description = "Optional. Glob patterns for files/directories to exclude."
            ),
            "recursive" to PropertySchema(
                type = "boolean",
                description = "Optional. Search directories recursively (controlled by ** in glob patterns)."
            ),
            "useDefaultExcludes" to PropertySchema(
                type = "boolean",
                description = "Optional. Apply default exclusion patterns. Defaults to true."
            )
        ),
        required = listOf("include")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: ReadManyFilesParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ReadManyFilesParams, ToolResult> {
        return ReadManyFilesToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ReadManyFilesParams {
        val include = (params["include"] as? List<*>)?.mapNotNull { it as? String }
            ?: throw IllegalArgumentException("include is required and must be an array")
        
        if (include.isEmpty()) {
            throw IllegalArgumentException("include must contain at least one pattern")
        }
        
        val exclude = (params["exclude"] as? List<*>)?.mapNotNull { it as? String }
        val recursive = params["recursive"] as? Boolean
        val useDefaultExcludes = params["useDefaultExcludes"] as? Boolean ?: true
        
        return ReadManyFilesParams(
            include = include,
            exclude = exclude,
            recursive = recursive,
            useDefaultExcludes = useDefaultExcludes
        )
    }
}
