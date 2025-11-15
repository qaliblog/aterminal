package com.rk.terminal.gemini.client

import com.rk.libcommons.alpineDir
import com.rk.terminal.api.ApiProviderManager
import com.rk.terminal.api.ApiProviderManager.KeysExhaustedException
import com.rk.terminal.gemini.tools.DeclarativeTool
import com.rk.terminal.gemini.core.*
import com.rk.terminal.gemini.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Main Gemini Client for making API calls and handling tool execution
 */
class GeminiClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String = alpineDir().absolutePath
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Content>()
    
    /**
     * Send a message and get streaming response
     */
    suspend fun sendMessageStream(
        userMessage: String,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ): Flow<GeminiStreamEvent> = flow {
        // Add user message to history
        chatHistory.add(
            Content(
                role = "user",
                parts = listOf(Part.TextPart(text = userMessage))
            )
        )
        
        // Get API key and model
        val apiKey = ApiProviderManager.getNextApiKey()
            ?: run {
                emit(GeminiStreamEvent.Error("No API keys configured"))
                return@flow
            }
        val model = ApiProviderManager.getCurrentModel()
        
        // Prepare request
        val requestBody = buildRequest(userMessage, model)
        
        // Make API call with retry
        val result = ApiProviderManager.makeApiCallWithRetry { key ->
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    makeApiCall(key, model, requestBody, onChunk, onToolCall, onToolResult)
                }
                Result.success(Unit)
            } catch (e: KeysExhaustedException) {
                Result.failure(e)
            } catch (e: Exception) {
                if (ApiProviderManager.isRateLimitError(e)) {
                    Result.failure(e)
                } else {
                    Result.failure(e)
                }
            }
        }
        
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is KeysExhaustedException) {
                emit(GeminiStreamEvent.KeysExhausted(error.message ?: "All keys exhausted"))
            } else {
                emit(GeminiStreamEvent.Error(error?.message ?: "Unknown error"))
            }
        } else {
            emit(GeminiStreamEvent.Done)
        }
    }
    
    private fun makeApiCall(
        apiKey: String,
        model: String,
        requestBody: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ) {
        // Google Gemini API endpoint - using SSE (Server-Sent Events) for streaming
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey"
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw IOException("API call failed: ${response.code} - $errorBody")
            }
            
            response.body?.let { body ->
                val source = body.source().buffer()
                var line: String?
                
                while (source.readUtf8Line().also { line = it } != null) {
                    val trimmedLine = line?.trim()
                    if (trimmedLine?.startsWith("data: ") == true) {
                        val jsonStr = trimmedLine.substring(6)
                        if (jsonStr == "[DONE]" || jsonStr.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(jsonStr)
                            processResponse(json, onChunk, onToolCall, onToolResult)
                        } catch (e: Exception) {
                            // Skip malformed JSON - log in debug mode
                            android.util.Log.d("GeminiClient", "Failed to parse JSON: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    private fun processResponse(
        json: JSONObject,
        onChunk: (String) -> Unit,
        onToolCall: (FunctionCall) -> Unit,
        onToolResult: (String, Map<String, Any>) -> Unit
    ) {
        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content")
            
            if (content != null) {
                val parts = content.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        
                        // Check for text
                        if (part.has("text")) {
                            val text = part.getString("text")
                            onChunk(text)
                            
                            // Add to history
                            if (chatHistory.isEmpty() || chatHistory.last().role != "model") {
                                chatHistory.add(
                                    Content(
                                        role = "model",
                                        parts = listOf(Part.TextPart(text = text))
                                    )
                                )
                            } else {
                                val lastContent = chatHistory.last()
                                val newParts = lastContent.parts + Part.TextPart(text = text)
                                chatHistory[chatHistory.size - 1] = lastContent.copy(parts = newParts)
                            }
                        }
                        
                        // Check for function call
                        if (part.has("functionCall")) {
                            val functionCallJson = part.getJSONObject("functionCall")
                            val name = functionCallJson.getString("name")
                            val argsJson = functionCallJson.getJSONObject("args")
                            val args = jsonObjectToMap(argsJson)
                            
                            val functionCall = FunctionCall(name = name, args = args)
                            onToolCall(functionCall)
                            
                            // Execute tool synchronously (in real implementation, this should be async)
                            val toolResult = try {
                                executeToolSync(name, args)
                            } catch (e: Exception) {
                                ToolResult(
                                    llmContent = "Error executing tool: ${e.message}",
                                    returnDisplay = "Error: ${e.message}",
                                    error = ToolError(
                                        message = e.message ?: "Unknown error",
                                        type = ToolErrorType.EXECUTION_ERROR
                                    )
                                )
                            }
                            
                            // Add function call and response to history
                            chatHistory.add(
                                Content(
                                    role = "model",
                                    parts = listOf(Part.FunctionCallPart(functionCall = functionCall))
                                )
                            )
                            
                            // Add function response
                            chatHistory.add(
                                Content(
                                    role = "user",
                                    parts = listOf(
                                        Part.FunctionResponsePart(
                                            functionResponse = FunctionResponse(
                                                name = name,
                                                response = mapOf("result" to toolResult.llmContent)
                                            )
                                        )
                                    )
                                )
                            )
                            
                            onToolResult(name, args)
                            
                            // Continue conversation with tool result - make another API call
                            // For now, we'll just add the result and let the user continue
                        }
                    }
                }
            }
        }
    }
    
    private fun executeToolSync(name: String, args: Map<String, Any>): ToolResult {
        val tool = toolRegistry.getTool(name)
            ?: return ToolResult(
                llmContent = "Tool not found: $name",
                returnDisplay = "Error: Tool not found",
                error = ToolError(
                    message = "Tool not found: $name",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        
        return try {
            val params = tool.validateParams(args)
                ?: return ToolResult(
                    llmContent = "Invalid parameters for tool: $name",
                    returnDisplay = "Error: Invalid parameters",
                    error = ToolError(
                        message = "Invalid parameters",
                        type = ToolErrorType.INVALID_PARAMETERS
                    )
                )
            
            @Suppress("UNCHECKED_CAST")
            val invocation = (tool as DeclarativeTool<Any, ToolResult>).createInvocation(params as Any)
            // Execute synchronously for now (in production, use coroutines properly)
            kotlinx.coroutines.runBlocking {
                invocation.execute(null, null)
            }
        } catch (e: Exception) {
            ToolResult(
                llmContent = "Error executing tool: ${e.message}",
                returnDisplay = "Error: ${e.message}",
                error = ToolError(
                    message = e.message ?: "Unknown error",
                    type = ToolErrorType.EXECUTION_ERROR
                )
            )
        }
    }
    
    private fun buildRequest(userMessage: String, model: String): JSONObject {
        val request = JSONObject()
        request.put("contents", JSONArray().apply {
            // Add chat history
            chatHistory.forEach { content ->
                val contentObj = JSONObject()
                contentObj.put("role", content.role)
                contentObj.put("parts", JSONArray().apply {
                    content.parts.forEach { part ->
                        when (part) {
                            is Part.TextPart -> {
                                val partObj = JSONObject()
                                partObj.put("text", part.text)
                                put(partObj)
                            }
                            is Part.FunctionCallPart -> {
                                val partObj = JSONObject()
                                val functionCallObj = JSONObject()
                                functionCallObj.put("name", part.functionCall.name)
                                functionCallObj.put("args", JSONObject(part.functionCall.args))
                                partObj.put("functionCall", functionCallObj)
                                put(partObj)
                            }
                            is Part.FunctionResponsePart -> {
                                val partObj = JSONObject()
                                val functionResponseObj = JSONObject()
                                functionResponseObj.put("name", part.functionResponse.name)
                                functionResponseObj.put("response", JSONObject(part.functionResponse.response))
                                partObj.put("functionResponse", functionResponseObj)
                                put(partObj)
                            }
                        }
                    }
                })
                put(contentObj)
            }
        })
        
        // Add tools
        val tools = JSONArray()
        val toolObj = JSONObject()
        val functionDeclarations = JSONArray()
        toolRegistry.getFunctionDeclarations().forEach { decl ->
            val declObj = JSONObject()
            declObj.put("name", decl.name)
            declObj.put("description", decl.description)
            declObj.put("parameters", functionParametersToJson(decl.parameters))
            functionDeclarations.put(declObj)
        }
        toolObj.put("functionDeclarations", functionDeclarations)
        tools.put(toolObj)
        request.put("tools", tools)
        
        return request
    }
    
    private fun functionParametersToJson(params: FunctionParameters): JSONObject {
        val json = JSONObject()
        json.put("type", params.type)
        json.put("properties", JSONObject().apply {
            params.properties.forEach { (key, schema) ->
                put(key, propertySchemaToJson(schema))
            }
        })
        json.put("required", JSONArray(params.required))
        return json
    }
    
    private fun propertySchemaToJson(schema: PropertySchema): JSONObject {
        val json = JSONObject()
        json.put("type", schema.type)
        json.put("description", schema.description)
        schema.enum?.let { json.put("enum", JSONArray(it)) }
        schema.items?.let { json.put("items", propertySchemaToJson(it)) }
        return json
    }
    
    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        json.keys().forEach { key ->
            val value = json.get(key)
            map[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
        }
        return map
    }
    
    private fun jsonArrayToList(json: JSONArray): List<Any> {
        val list = mutableListOf<Any>()
        for (i in 0 until json.length()) {
            val value = json.get(i)
            list.add(
                when (value) {
                    is JSONObject -> jsonObjectToMap(value)
                    is JSONArray -> jsonArrayToList(value)
                    else -> value
                }
            )
        }
        return list
    }
    
    /**
     * Generate content with web search capability
     */
    suspend fun generateContentWithWebSearch(
        query: String,
        signal: CancellationSignal? = null
    ): GenerateContentResponseWithGrounding {
        val apiKey = ApiProviderManager.getNextApiKey()
            ?: throw Exception("No API keys configured")
        
        // Use configured model, or default to web search capable model
        val model = ApiProviderManager.getCurrentModel().takeIf { it.isNotBlank() }
            ?: "gemini-2.0-flash-exp" // Default to web search capable model
        
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", query)
                        })
                    })
                })
            })
        }
        
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    throw IOException("API call failed: ${response.code} - $errorBody")
                }
                
                val responseBody = response.body?.string() ?: "{}"
                val json = JSONObject(responseBody)
                
                parseResponseWithGrounding(json)
            }
        }
    }
    
    private fun parseResponseWithGrounding(json: JSONObject): GenerateContentResponseWithGrounding {
        val candidates = json.optJSONArray("candidates")
        val candidateList = mutableListOf<CandidateWithGrounding>()
        
        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val candidateJson = candidates.getJSONObject(i)
                val content = candidateJson.optJSONObject("content")
                val groundingMetadataJson = candidateJson.optJSONObject("groundingMetadata")
                
                val contentObj = if (content != null) {
                    parseContent(content)
                } else {
                    null
                }
                
                val groundingMetadata = if (groundingMetadataJson != null) {
                    parseGroundingMetadata(groundingMetadataJson)
                } else {
                    null
                }
                
                candidateList.add(
                    CandidateWithGrounding(
                        content = contentObj,
                        finishReason = candidateJson.optString("finishReason"),
                        groundingMetadata = groundingMetadata
                    )
                )
            }
        }
        
        val usageMetadataJson = json.optJSONObject("usageMetadata")
        val usageMetadata = if (usageMetadataJson != null) {
            UsageMetadata(
                promptTokenCount = usageMetadataJson.optInt("promptTokenCount").takeIf { it > 0 },
                candidatesTokenCount = usageMetadataJson.optInt("candidatesTokenCount").takeIf { it > 0 },
                totalTokenCount = usageMetadataJson.optInt("totalTokenCount").takeIf { it > 0 }
            )
        } else {
            null
        }
        
        return GenerateContentResponseWithGrounding(
            candidates = candidateList,
            finishReason = json.optString("finishReason"),
            usageMetadata = usageMetadata
        )
    }
    
    private fun parseGroundingMetadata(json: JSONObject): GroundingMetadata {
        val chunksJson = json.optJSONArray("groundingChunks")
        val chunks = if (chunksJson != null) {
            (0 until chunksJson.length()).mapNotNull { i ->
                val chunkJson = chunksJson.getJSONObject(i)
                val webJson = chunkJson.optJSONObject("web")
                val web = if (webJson != null) {
                    GroundingChunkWeb(
                        uri = webJson.optString("uri").takeIf { it.isNotEmpty() },
                        title = webJson.optString("title").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
                if (web != null) {
                    GroundingChunkItem(web = web)
                } else {
                    null
                }
            }
        } else {
            null
        }
        
        val supportsJson = json.optJSONArray("groundingSupports")
        val supports = if (supportsJson != null) {
            (0 until supportsJson.length()).mapNotNull { i ->
                val supportJson = supportsJson.getJSONObject(i)
                val segmentJson = supportJson.optJSONObject("segment")
                val segment = if (segmentJson != null) {
                    GroundingSupportSegment(
                        startIndex = segmentJson.optInt("startIndex"),
                        endIndex = segmentJson.optInt("endIndex"),
                        text = segmentJson.optString("text").takeIf { it.isNotEmpty() }
                    )
                } else {
                    null
                }
                
                val chunkIndicesJson = supportJson.optJSONArray("groundingChunkIndices")
                val chunkIndices = if (chunkIndicesJson != null) {
                    (0 until chunkIndicesJson.length()).map { chunkIndicesJson.getInt(it) }
                } else {
                    null
                }
                
                if (segment != null || chunkIndices != null) {
                    GroundingSupportItem(
                        segment = segment,
                        groundingChunkIndices = chunkIndices
                    )
                } else {
                    null
                }
            }
        } else {
            null
        }
        
        return GroundingMetadata(
            groundingChunks = chunks,
            groundingSupports = supports
        )
    }
    
    private fun parseContent(json: JSONObject): Content {
        val role = json.getString("role")
        val partsJson = json.getJSONArray("parts")
        val parts = (0 until partsJson.length()).mapNotNull { i ->
            val partJson = partsJson.getJSONObject(i)
            when {
                partJson.has("text") -> {
                    Part.TextPart(text = partJson.getString("text"))
                }
                partJson.has("functionCall") -> {
                    val fcJson = partJson.getJSONObject("functionCall")
                    Part.FunctionCallPart(
                        functionCall = FunctionCall(
                            name = fcJson.getString("name"),
                            args = jsonObjectToMap(fcJson.getJSONObject("args"))
                        )
                    )
                }
                partJson.has("functionResponse") -> {
                    val frJson = partJson.getJSONObject("functionResponse")
                    Part.FunctionResponsePart(
                        functionResponse = FunctionResponse(
                            name = frJson.getString("name"),
                            response = jsonObjectToMap(frJson.getJSONObject("response"))
                        )
                    )
                }
                else -> null
            }
        }
        
        return Content(role = role, parts = parts)
    }
    
    fun getHistory(): List<Content> = chatHistory.toList()
    
    fun resetChat() {
        chatHistory.clear()
    }
}

sealed class GeminiStreamEvent {
    data class Chunk(val text: String) : GeminiStreamEvent()
    data class ToolCall(val functionCall: FunctionCall) : GeminiStreamEvent()
    data class ToolResult(val toolName: String, val result: ToolResult) : GeminiStreamEvent()
    data class Error(val message: String) : GeminiStreamEvent()
    data class KeysExhausted(val message: String) : GeminiStreamEvent()
    object Done : GeminiStreamEvent()
}
