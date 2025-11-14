package com.rk.terminal.gemini.tools

import com.rk.terminal.gemini.client.GeminiClient
import com.rk.terminal.gemini.core.*
import com.rk.terminal.gemini.core.FunctionDeclaration
import com.rk.terminal.gemini.core.FunctionParameters
import com.rk.terminal.gemini.core.PropertySchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

data class WebSearchToolParams(
    val query: String
)

class WebSearchToolInvocation(
    private val params: WebSearchToolParams,
    private val geminiClient: GeminiClient
) : ToolInvocation<WebSearchToolParams, ToolResult> {
    
    override val params: WebSearchToolParams = params
    
    override fun getDescription(): String {
        return "Searching the web for: \"${params.query}\""
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
                llmContent = "Web search cancelled",
                returnDisplay = "Cancelled"
            )
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val response = geminiClient.generateContentWithWebSearch(params.query, signal)
                
                val candidate = response.candidates?.firstOrNull()
                if (candidate == null) {
                    return@withContext ToolResult(
                        llmContent = "No search results or information found for query: \"${params.query}\"",
                        returnDisplay = "No information found"
                    )
                }
                
                // Extract text from content
                val responseText = candidate.content?.parts
                    ?.filterIsInstance<Part.TextPart>()
                    ?.joinToString("") { it.text }
                    ?: ""
                
                if (responseText.trim().isEmpty()) {
                    return@withContext ToolResult(
                        llmContent = "No search results or information found for query: \"${params.query}\"",
                        returnDisplay = "No information found"
                    )
                }
                
                val groundingMetadata = candidate.groundingMetadata
                val sources = groundingMetadata?.groundingChunks
                val groundingSupports = groundingMetadata?.groundingSupports
                
                var modifiedResponseText = responseText
                val sourceListFormatted = mutableListOf<String>()
                
                // Process sources
                if (sources != null && sources.isNotEmpty()) {
                    sources.forEachIndexed { index, source ->
                        val title = source.web?.title ?: "Untitled"
                        val uri = source.web?.uri ?: "No URI"
                        sourceListFormatted.add("[$index] $title ($uri)")
                    }
                    
                    // Insert citation markers using grounding supports
                    if (groundingSupports != null && groundingSupports.isNotEmpty()) {
                        val insertions = mutableListOf<Pair<Int, String>>()
                        
                        groundingSupports.forEach { support ->
                            if (support.segment != null && support.groundingChunkIndices != null) {
                                val citationMarker = support.groundingChunkIndices
                                    .map { "[${it + 1}]" }
                                    .joinToString("")
                                insertions.add(support.segment.endIndex to citationMarker)
                            }
                        }
                        
                        // Sort by index descending to insert from end to start
                        insertions.sortByDescending { it.first }
                        
                        // Insert markers
                        var currentText = modifiedResponseText
                        for ((index, marker) in insertions) {
                            if (index <= currentText.toByteArray(StandardCharsets.UTF_8).size) {
                                // Convert byte index to character index (approximate)
                                val charIndex = minOf(index, currentText.length)
                                currentText = currentText.substring(0, charIndex) + marker + currentText.substring(charIndex)
                            }
                        }
                        modifiedResponseText = currentText
                    }
                    
                    // Append sources list
                    if (sourceListFormatted.isNotEmpty()) {
                        modifiedResponseText += "\n\nSources:\n${sourceListFormatted.joinToString("\n")}"
                    }
                }
                
                updateOutput?.invoke("Web search completed")
                
                ToolResult(
                    llmContent = "Web search results for \"${params.query}\":\n\n$modifiedResponseText",
                    returnDisplay = "Search results for \"${params.query}\" returned"
                )
            } catch (e: Exception) {
                ToolResult(
                    llmContent = "Error during web search for query \"${params.query}\": ${e.message}",
                    returnDisplay = "Error performing web search",
                    error = ToolError(
                        message = e.message ?: "Unknown error",
                        type = ToolErrorType.EXECUTION_ERROR
                    )
                )
            }
        }
    }
}

class WebSearchTool(
    private val geminiClient: GeminiClient
) : DeclarativeTool<WebSearchToolParams, ToolResult>() {
    
    override val name = "web_search"
    override val displayName = "WebSearch"
    override val description = "Searches the web for information using Google's search capabilities. Returns relevant results with source citations."
    
    override val parameterSchema = FunctionParameters(
        type = "object",
        properties = mapOf(
            "query" to PropertySchema(
                type = "string",
                description = "The search query."
            )
        ),
        required = listOf("query")
    )
    
    override fun getFunctionDeclaration(): FunctionDeclaration {
        return FunctionDeclaration(
            name = name,
            description = description,
            parameters = parameterSchema
        )
    }
    
    override fun createInvocation(
        params: WebSearchToolParams,
        toolName: String?,
        toolDisplayName: String?
    ): ToolInvocation<WebSearchToolParams, ToolResult> {
        return WebSearchToolInvocation(params, geminiClient)
    }
    
    override fun validateAndConvertParams(params: Map<String, Any>): WebSearchToolParams {
        val query = params["query"] as? String
            ?: throw IllegalArgumentException("query is required")
        
        if (query.trim().isEmpty()) {
            throw IllegalArgumentException("query must be non-empty")
        }
        
        return WebSearchToolParams(query = query)
    }
}
