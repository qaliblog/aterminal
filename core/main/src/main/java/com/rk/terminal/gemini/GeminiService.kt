package com.rk.terminal.gemini

import com.rk.libcommons.alpineDir
import com.rk.terminal.gemini.client.GeminiClient
import com.rk.terminal.gemini.client.GeminiStreamEvent
import com.rk.terminal.gemini.tools.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Service for initializing and managing Gemini client with tools
 */
object GeminiService {
    private var client: GeminiClient? = null
    
    fun initialize(workspaceRoot: String = alpineDir().absolutePath): GeminiClient {
        if (client == null) {
            val toolRegistry = ToolRegistry()
            
            // Register all tools
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
            
            val newClient = GeminiClient(toolRegistry, workspaceRoot)
            client = newClient
            
            // Register web search tool (requires client reference)
            toolRegistry.registerTool(WebSearchTool(newClient))
            
            // Note: MCP tools would be registered here when MCP client is available
            // For now, MCP tools are created on-demand
        }
        return client!!
    }
    
    fun getClient(): GeminiClient? = client
    
    fun reset() {
        client?.resetChat()
    }
}
