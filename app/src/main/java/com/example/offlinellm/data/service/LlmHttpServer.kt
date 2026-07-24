package com.example.offlinellm.data.service

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val created: Long = System.currentTimeMillis() / 1000,
    val owned_by: String = "local"
)

@Serializable
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelInfo>
)

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    val max_tokens: Int = -1
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage = ChatMessage("assistant", ""),
    val finish_reason: String = "stop"
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ChatCompletionResponse(
    val id: String = "chatcmpl-local-${System.currentTimeMillis()}",
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "local",
    val choices: List<ChatChoice>,
    val usage: Usage = Usage()
)

class LlmHttpServer(
    private val port: Int = 8080,
    private val generate: suspend (userPrompt: String, systemPrompt: String) -> Flow<String> =
        { _, _ -> emptyFlow() },
    private val modelId: () -> String = { "unknown" }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ApplicationEngine? = null
    private val jsonEngine = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun start() {
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(jsonEngine)
            }
            routing {
                get("/v1/models") {
                    val response = ModelsResponse(data = listOf(ModelInfo(id = modelId())))
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = jsonEngine.encodeToString(response)
                    )
                }
                post("/v1/chat/completions") {
                    val text = call.receiveText()
                    val req = jsonEngine.decodeFromString<ChatCompletionRequest>(text)
                    val (userPrompt, systemPrompt) = splitMessages(req.messages)
                    val result = buildString {
                        val flow = generate(userPrompt, systemPrompt)
                        flow.collect { append(it) }
                    }
                    val response = ChatCompletionResponse(
                        model = req.model.ifEmpty { modelId() },
                        choices = listOf(ChatChoice(message = ChatMessage("assistant", result)))
                    )
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = jsonEngine.encodeToString(response)
                    )
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        scope.cancel()
    }

    companion object {
        /**
         * OpenAI-style messages → engine (user + system) for ChatML.
         * system roles become systemPrompt; everything else is the user/dialogue prompt.
         */
        fun splitMessages(messages: List<ChatMessage>): Pair<String, String> {
            val systems = ArrayList<String>()
            val rest = ArrayList<ChatMessage>()
            for (m in messages) {
                if (m.role.equals("system", ignoreCase = true)) {
                    if (m.content.isNotBlank()) systems.add(m.content)
                } else {
                    rest.add(m)
                }
            }
            val systemPrompt = systems.joinToString("\n").trim()
            val userPrompt = when {
                rest.isEmpty() -> ""
                rest.size == 1 && rest[0].role.equals("user", ignoreCase = true) ->
                    rest[0].content
                else -> rest.joinToString("\n") { "${it.role}: ${it.content}" }
            }
            return userPrompt to systemPrompt
        }
    }
}
