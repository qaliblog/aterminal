package com.rk.terminal.gemini.client

import com.rk.terminal.gemini.core.FunctionCall
import com.rk.terminal.gemini.client.GeminiStreamEvent
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
 * Ollama Client for local LLM inference
 */
class OllamaClient(
    private val toolRegistry: ToolRegistry,
    private val workspaceRoot: String,
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.2"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val chatHistory = mutableListOf<Map<String, Any>>()
    
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
        chatHistory.add(mapOf("role" to "user", "content" to userMessage))
        
        try {
            // For now, simple streaming chat - tool support can be added later
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    chatHistory.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg["role"])
                            put("content", msg["content"])
                        })
                    }
                })
                put("stream", true)
            }
            
            val request = Request.Builder()
                .url("$baseUrl/api/chat")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = try {
                        response.body?.string() ?: "Unknown error"
                    } catch (e: Exception) {
                        "Failed to read error body: ${e.message}"
                    }
                    emit(GeminiStreamEvent.Error("Ollama API error: ${response.code} - $errorBody"))
                    return@flow
                }
                
                response.body?.source()?.let { source ->
                    var buffer = ""
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        
                        try {
                            val json = JSONObject(line)
                            val message = json.optJSONObject("message")
                            val content = message?.optString("content", "") ?: ""
                            
                            if (content.isNotEmpty()) {
                                buffer += content
                                onChunk(content)
                                emit(GeminiStreamEvent.Chunk(content))
                            }
                            
                            if (json.optBoolean("done", false)) {
                                // Add assistant response to history
                                chatHistory.add(mapOf("role" to "assistant", "content" to buffer))
                                break
                            }
                        } catch (e: Exception) {
                            // Skip malformed JSON
                            android.util.Log.d("OllamaClient", "Failed to parse JSON: ${e.message}")
                            emit(GeminiStreamEvent.Error("Failed to parse Ollama response: ${e.message ?: "Unknown error"}"))
                            break
                        }
                    }
                }
            }
        } catch (e: IOException) {
            val errorMsg = e.message ?: "Network error"
            android.util.Log.e("OllamaClient", "Network error", e)
            emit(GeminiStreamEvent.Error("Network error: $errorMsg"))
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            android.util.Log.e("OllamaClient", "Error", e)
            emit(GeminiStreamEvent.Error("Error: $errorMsg"))
        }
    }
    
    fun resetChat() {
        chatHistory.clear()
    }
    
    fun getHistory(): List<Map<String, Any>> = chatHistory.toList()
}
