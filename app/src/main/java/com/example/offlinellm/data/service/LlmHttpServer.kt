package com.example.offlinellm.data.service

import android.content.Context
import com.example.offlinellm.llama.LlamaInferenceEngine
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * HTTP server that hosts the LLM model as an OpenAI-compatible API.
 * Allows Kai, OpenClaw, or any HTTP client to use the phone as an LLM host.
 *
 * Endpoints:
 *   POST /v1/chat/completions — OpenAI-compatible chat
 *   GET  /v1/models — list available models
 *   GET  /health — server status
 */
class LlmHttpServer(
    private val context: Context,
    private val modelPath: String,
    private val modelName: String = "local-model",
    port: Int = 8080
) {
    private var server: ApplicationEngine? = null
    private var engine: LlamaInferenceEngine? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start the HTTP server. */
    suspend fun start() {
        engine = LlamaInferenceEngine(
            modelPath = modelPath,
            nCtx = 4096,
            nGpuLayers = 99
        ).also { it.load() }

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }

            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok", "model" to modelName))
                }

                get("/v1/models") {
                    call.respond(mapOf(
                        "object" to "list",
                        "data" to listOf(mapOf(
                            "id" to modelName,
                            "object" to "model",
                            "created" to System.currentTimeMillis() / 1000,
                            "owned_by" to "offlinellm"
                        ))
                    ))
                }

                post("/v1/chat/completions") {
                    val body = call.receive<ChatRequest>()
                    val prompt = body.messages.joinToString("\n") { msg ->
                        when (msg.role) {
                            "system" -> "System: ${msg.content}"
                            "user" -> "User: ${msg.content}"
                            "assistant" -> "Assistant: ${msg.content}"
                            else -> msg.content
                        }
                    } + "\nAssistant:"

                    val response = engine?.generate(prompt) ?: "Error: engine not loaded"

                    call.respond(ChatResponse(
                        id = "chatcmpl-${System.currentTimeMillis()}",
                        model = modelName,
                        choices = listOf(Choice(
                            index = 0,
                            message = ChatMessage(role = "assistant", content = response.trim())
                        ))
                    ))
                }
            }
        }.start(wait = false)
    }

    /** Stop the server. */
    fun stop() {
        server?.stop(1000, 2000)
        scope.cancel()
    }

    @Serializable
    data class ChatRequest(
        val model: String = "",
        val messages: List<ChatMessage> = emptyList(),
        val stream: Boolean = false,
        val max_tokens: Int = 2048,
        val temperature: Float = 0.7f
    )

    @Serializable
    data class ChatMessage(
        val role: String = "user",
        val content: String = ""
    )

    @Serializable
    data class ChatResponse(
        val id: String,
        val model: String,
        val choices: List<Choice>
    )

    @Serializable
    data class Choice(
        val index: Int,
        val message: ChatMessage
    )
}
