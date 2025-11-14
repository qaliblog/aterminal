package com.rk.terminal.gemini.tools

import com.rk.libcommons.alpineDir
import com.rk.terminal.gemini.core.FunctionDeclaration
import com.rk.terminal.gemini.core.FunctionParameters
import com.rk.terminal.gemini.core.PropertySchema
import java.io.File

data class ShellToolParams(
    val command: String,
    val description: String? = null,
    val dir_path: String? = null
)

class ShellToolInvocation(
    private val params: ShellToolParams,
    private val workspaceRoot: String = alpineDir().absolutePath
) : ToolInvocation<ShellToolParams, ToolResult> {
    
    override val params: ShellToolParams = params
    
    override fun getDescription(): String {
        var desc = params.command
        if (params.dir_path != null) {
            desc += " [in ${params.dir_path}]"
        }
        if (params.description != null) {
            desc += " (${params.description})"
        }
        return desc
    }
    
    override fun toolLocations(): List<ToolLocation> {
        return emptyList()
    }
    
    override suspend fun execute(
        signal: CancellationSignal?,
        updateOutput: ((String) -> Unit)?
    ): ToolResult {
        if (signal?.isAborted() == true) {
            return ToolResult(
                llmContent = "Command cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return try {
            val workingDir = if (params.dir_path != null) {
                File(workspaceRoot, params.dir_path)
            } else {
                File(workspaceRoot)
            }
            
            val process = ProcessBuilder()
                .command("sh", "-c", params.command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                updateOutput?.invoke(output)
                ToolResult(
                    llmContent = output,
                    returnDisplay = "Command executed successfully"
                )
            } else {
                ToolResult(
                    llmContent = "Command failed with exit code $exitCode:\n$output",
                    returnDisplay = "Error: Exit code $exitCode",
                    error = ToolError(
                        message = "Command failed with exit code $exitCode",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error executing command: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
}

class ShellTool(
    private val workspaceRoot: String = alpineDir().absolutePath
) : DeclarativeTool<ShellToolParams, ToolResult>() {
    
    override val name = "shell"
    override val displayName = "Shell"
    override val description = "Executes a shell command and returns the output. Use this to run terminal commands, scripts, and interact with the file system."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "command" to PropertySchema(
                type = "string",
                description = "The shell command to execute."
            ),
            "description" to PropertySchema(
                type = "string",
                description = "Optional description of what the command does."
            ),
            "dir_path" to PropertySchema(
                type = "string",
                description = "Optional directory path to execute the command in."
            )
        ),
        required = listOf("command")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: ShellToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<ShellToolParams, ToolResult> {
        return ShellToolInvocation(params, workspaceRoot)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): ShellToolParams {
        val command = params["command"] as? String
            ?: throw IllegalArgumentException("command is required")
        
        if (command.trim().isEmpty()) {
            throw IllegalArgumentException("command must be non-empty")
        }
        
        return ShellToolParams(
            command = command,
            description = params["description"] as? String,
            dir_path = params["dir_path"] as? String
        )
    }
}
