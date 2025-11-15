package com.rk.terminal.gemini

import com.rk.libcommons.alpineDir
import com.rk.terminal.gemini.client.GeminiClient
import com.rk.terminal.gemini.client.GeminiStreamEvent
import com.rk.terminal.gemini.client.OllamaClient
import com.rk.terminal.gemini.tools.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for initializing and managing Gemini/Ollama client with tools
 */
object GeminiService {
    private var client: GeminiClient? = null
    private var ollamaClient: OllamaClient? = null
    private var useOllama: Boolean = false
    private var currentWorkspaceRoot: String = alpineDir().absolutePath
    
    fun initialize(workspaceRoot: String = alpineDir().absolutePath, useOllama: Boolean = false, ollamaUrl: String = "http://localhost:11434", ollamaModel: String = "llama3.2"): Any {
        val workspaceChanged = currentWorkspaceRoot != workspaceRoot
        val useOllamaChanged = this.useOllama != useOllama
        
        currentWorkspaceRoot = workspaceRoot
        this.useOllama = useOllama
        
        if (useOllama) {
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
            if (ollamaClient == null || workspaceChanged || useOllamaChanged) {
                val toolRegistry = ToolRegistry()
                registerAllTools(toolRegistry, workspaceRoot)
                ollamaClient = OllamaClient(toolRegistry, workspaceRoot, ollamaUrl, ollamaModel)
            }
            return ollamaClient!!
        } else {
            // Recreate client if workspace changed, useOllama changed, or client doesn't exist
            if (client == null || workspaceChanged || useOllamaChanged) {
                val toolRegistry = ToolRegistry()
                registerAllTools(toolRegistry, workspaceRoot)
                
                val newClient = GeminiClient(toolRegistry, workspaceRoot)
                client = newClient
                
                // Register web search tool (requires client reference)
                toolRegistry.registerTool(WebSearchTool(newClient))
            }
            return client!!
        }
    }
    
    private fun registerAllTools(toolRegistry: ToolRegistry, workspaceRoot: String) {
        toolRegistry.registerTool(ReadFileTool(workspaceRoot))
        toolRegistry.registerTool(WriteFileTool(workspaceRoot))
        toolRegistry.registerTool(EditTool(workspaceRoot))
        toolRegistry.registerTool(SmartEditTool(workspaceRoot))
        toolRegistry.registerTool(ShellTool(workspaceRoot))
        toolRegistry.registerTool(LSTool(workspaceRoot))
        toolRegistry.registerTool(GrepTool(workspaceRoot))
        toolRegistry.registerTool(RipGrepTool(workspaceRoot))
        toolRegistry.registerTool(ReadManyFilesTool(workspaceRoot))
        toolRegistry.registerTool(GlobTool(workspaceRoot))
        toolRegistry.registerTool(WriteTodosTool())
        toolRegistry.registerTool(WebFetchTool())
        toolRegistry.registerTool(MemoryTool(workspaceRoot))
    }
    
    fun getClient(): GeminiClient? = client
    fun getOllamaClient(): OllamaClient? = ollamaClient
    fun isUsingOllama(): Boolean = useOllama
    fun getWorkspaceRoot(): String = currentWorkspaceRoot
    
    fun reset() {
        client?.resetChat()
        ollamaClient?.resetChat()
    }
}
