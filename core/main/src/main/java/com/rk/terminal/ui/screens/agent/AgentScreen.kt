package com.rk.terminal.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.terminal.api.ApiProviderManager
import com.rk.terminal.api.KeysExhaustedException
import com.rk.terminal.gemini.GeminiService
import com.rk.terminal.gemini.client.GeminiStreamEvent
import com.rk.terminal.ui.activities.terminal.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    mainActivity: MainActivity,
    sessionId: String
) {
    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }
    var showKeysExhaustedDialog by remember { mutableStateOf(false) }
    var lastFailedPrompt by remember { mutableStateOf<String?>(null) }
    var retryCountdown by remember { mutableStateOf(0) }
    var currentResponseText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    
    // Initialize Gemini client
    val geminiClient = remember {
        GeminiService.initialize()
    }
    
    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gemini AI Agent",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Ready for gemini-cli integration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
        
        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    WelcomeMessage()
                }
            } else {
                items(messages) { message ->
                    MessageBubble(message = message)
                }
            }
        }
        
        // Input area
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent...") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    val userMessage = AgentMessage(
                                        text = inputText,
                                        isUser = true,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    messages = messages + userMessage
                                    val prompt = inputText
                                    inputText = ""
                                    
                                    // Send to Gemini API with tools
                                    scope.launch {
                                        val loadingMessage = AgentMessage(
                                            text = "Thinking...",
                                            isUser = false,
                                            timestamp = System.currentTimeMillis()
                                        )
                                        messages = messages + loadingMessage
                                        currentResponseText = ""
                                        
                                        try {
                                            val stream = geminiClient.sendMessageStream(
                                                userMessage = prompt,
                                                onChunk = { chunk ->
                                                    currentResponseText += chunk
                                                    // Update the last message with accumulated text
                                                    val currentMessages = messages.dropLast(1)
                                                    messages = currentMessages + AgentMessage(
                                                        text = currentResponseText,
                                                        isUser = false,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                },
                                                onToolCall = { functionCall ->
                                                    // Add tool call message
                                                    val toolMessage = AgentMessage(
                                                        text = "🔧 Calling tool: ${functionCall.name}",
                                                        isUser = false,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    messages = messages + toolMessage
                                                },
                                                onToolResult = { toolName, args ->
                                                    // Add tool result message
                                                    val resultMessage = AgentMessage(
                                                        text = "✅ Tool '$toolName' completed",
                                                        isUser = false,
                                                        timestamp = System.currentTimeMillis()
                                                    )
                                                    messages = messages + resultMessage
                                                }
                                            )
                                            
                                            // Collect stream events
                                            stream.collect { event ->
                                                when (event) {
                                                    is GeminiStreamEvent.Chunk -> {
                                                        currentResponseText += event.text
                                                        val currentMessages = messages.dropLast(1)
                                                        messages = currentMessages + AgentMessage(
                                                            text = currentResponseText,
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                    }
                                                    is GeminiStreamEvent.ToolCall -> {
                                                        val toolMessage = AgentMessage(
                                                            text = "🔧 Calling tool: ${event.functionCall.name}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages + toolMessage
                                                    }
                                                    is GeminiStreamEvent.ToolResult -> {
                                                        val resultMessage = AgentMessage(
                                                            text = "✅ Tool '${event.toolName}' completed: ${event.result.returnDisplay}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages + resultMessage
                                                    }
                                                    is GeminiStreamEvent.Error -> {
                                                        val errorMessage = AgentMessage(
                                                            text = "❌ Error: ${event.message}",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages.dropLast(1) + errorMessage
                                                    }
                                                    is GeminiStreamEvent.KeysExhausted -> {
                                                        lastFailedPrompt = prompt
                                                        showKeysExhaustedDialog = true
                                                        val exhaustedMessage = AgentMessage(
                                                            text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                            isUser = false,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages = messages.dropLast(1) + exhaustedMessage
                                                    }
                                                    is GeminiStreamEvent.Done -> {
                                                        // Stream completed
                                                    }
                                                }
                                            }
                                        } catch (e: KeysExhaustedException) {
                                            lastFailedPrompt = prompt
                                            showKeysExhaustedDialog = true
                                            val exhaustedMessage = AgentMessage(
                                                text = "⚠️ Keys are exhausted\n\nAll API keys are rate limited. Use 'Wait and Retry' to retry after a delay.",
                                                isUser = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            messages = messages.dropLast(1) + exhaustedMessage
                                        } catch (e: Exception) {
                                            val errorMessage = AgentMessage(
                                                text = "❌ Error: ${e.message ?: "Unknown error"}",
                                                isUser = false,
                                                timestamp = System.currentTimeMillis()
                                            )
                                            messages = messages.dropLast(1) + errorMessage
                                        }
                                    }
                                }
                            },
                            enabled = inputText.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = if (inputText.isNotBlank()) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                )
            }
        }
    }
    
    // Keys Exhausted Dialog with Wait and Retry
    if (showKeysExhaustedDialog) {
        KeysExhaustedDialog(
            onDismiss = { showKeysExhaustedDialog = false },
            onWaitAndRetry = { waitSeconds ->
                showKeysExhaustedDialog = false
                retryCountdown = waitSeconds
                
                scope.launch {
                    while (retryCountdown > 0) {
                        delay(1000)
                        retryCountdown--
                    }
                    
                    // Retry the last prompt
                    lastFailedPrompt?.let { prompt ->
                        inputText = prompt
                        // Trigger send (simulate button click)
                        val userMessage = AgentMessage(
                            text = prompt,
                            isUser = true,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = messages + userMessage
                        
                        val loadingMessage = AgentMessage(
                            text = "Retrying...",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        )
                        messages = messages + loadingMessage
                        
                        val result = ApiProviderManager.makeApiCallWithRetry { apiKey ->
                            kotlinx.coroutines.delay(1000)
                            if (kotlin.random.Random.nextDouble() < 0.3) {
                                Result.failure(Exception("Rate limit exceeded (RPM)"))
                            } else {
                                Result.success("Retry successful! Response for: \"$prompt\"")
                            }
                        }
                        
                        val currentMessages = messages.dropLast(1)
                        val responseMessage = if (result.isSuccess) {
                            AgentMessage(
                                text = result.getOrNull() ?: "No response",
                                isUser = false,
                                timestamp = System.currentTimeMillis()
                            )
                        } else {
                            val error = result.exceptionOrNull()
                            if (error is KeysExhaustedException) {
                                lastFailedPrompt = prompt
                                showKeysExhaustedDialog = true
                                AgentMessage(
                                    text = "⚠️ Keys are still exhausted. Please wait longer or add more API keys.",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            } else {
                                AgentMessage(
                                    text = "Error: ${error?.message ?: "Unknown error"}",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                )
                            }
                        }
                        
                        messages = currentMessages + responseMessage
                    }
                }
            }
        )
    }
    
    // Show countdown if retrying
    if (retryCountdown > 0) {
        LaunchedEffect(retryCountdown) {
            // Countdown is handled in the coroutine above
        }
    }
}

@Composable
fun KeysExhaustedDialog(
    onDismiss: () -> Unit,
    onWaitAndRetry: (Int) -> Unit
) {
    var waitSeconds by remember { mutableStateOf(60) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Keys are Exhausted")
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "All API keys are rate limited. You can wait and retry, or add more API keys in settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wait for:")
                    OutlinedTextField(
                        value = waitSeconds.toString(),
                        onValueChange = { 
                            it.toIntOrNull()?.let { seconds ->
                                if (seconds >= 0) waitSeconds = seconds
                            }
                        },
                        modifier = Modifier.width(100.dp),
                        label = { Text("Seconds") }
                    )
                    Text("seconds")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onWaitAndRetry(waitSeconds) }
            ) {
                Text("Wait and Retry")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun WelcomeMessage() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Welcome to Gemini AI Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This agent will be integrated with gemini-cli to provide AI-powered assistance for your terminal workflow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Features coming soon:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Code generation and assistance\n• Terminal command suggestions\n• File operations guidance\n• Project analysis and recommendations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun MessageBubble(message: AgentMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 8.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (message.isUser) 16.dp else 4.dp,
                bottomEnd = if (message.isUser) 4.dp else 16.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontSize = 10.sp
                )
            }
        }
        
        if (message.isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

data class AgentMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)

fun formatTimestamp(timestamp: Long): String {
    val time = java.util.Date(timestamp)
    val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return format.format(time)
}
