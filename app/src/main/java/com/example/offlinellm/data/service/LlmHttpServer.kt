package com.example.offlinellm.data.service

import com.example.offlinellm.data.local.AppLogger
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
data class ChatMessage(val role: String = "user", val content: String = "")

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
    val finish_reason: String? = "stop"
)

@Serializable
data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class ChatStreamChoice(
    val index: Int = 0,
    val delta: ChatStreamDelta = ChatStreamDelta(),
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ChatCompletionResponse(
    val id: String = "chatcmpl-local",
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "local",
    val choices: List<ChatChoice>,
    val usage: Usage = Usage()
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "local",
    val choices: List<ChatStreamChoice>
)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String = "server_error",
    val code: String? = null
)

/**
 * Minimal OpenAI-compatible HTTP surface for LAN clients (curl, OpenClaw, SillyTavern, etc.).
 *
 * Engine stream semantics: each Flow emission is a **full cleaned assistant snapshot**
 * (not a token delta). HTTP layer converts snapshots → deltas for SSE / final JSON.
 */
class LlmHttpServer(
    private val port: Int = 8080,
    private val host: String = "0.0.0.0",
    private val generate: suspend (userPrompt: String, systemPrompt: String) -> Flow<String> =
        { _, _ -> emptyFlow() },
    private val modelId: () -> String = { "unknown" }
) {
    private var server: ApplicationEngine? = null
    private val jsonEngine = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        isLenient = true
    }
    private val busy = AtomicBoolean(false)

    fun start() {
        server = embeddedServer(Netty, host = host, port = port) {
            install(ContentNegotiation) {
                json(jsonEngine)
            }
            intercept(ApplicationCallPipeline.Plugins) {
                // CORS for browser tools (Open WebUI etc.)
                call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.headers.append(HttpHeaders.AccessControlAllowHeaders, "*")
                call.response.headers.append(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
                if (call.request.httpMethod == HttpMethod.Options) {
                    call.respond(HttpStatusCode.NoContent)
                    finish()
                }
            }
            routing {
                get("/") {
                    call.respondText(
                        "OfflineLLM OpenAI-compatible server\n" +
                            "GET  /health\n" +
                            "GET  /v1/models\n" +
                            "POST /v1/chat/completions\n",
                        ContentType.Text.Plain
                    )
                }
                get("/health") {
                    call.respondText(
                        """{"ok":true,"model":${jsonEngine.encodeToString(modelId())},"busy":${busy.get()}}""",
                        ContentType.Application.Json
                    )
                }
                // Common aliases
                get("/v1") {
                    call.respondText(
                        """{"object":"api","status":"ok","model":${jsonEngine.encodeToString(modelId())}}""",
                        ContentType.Application.Json
                    )
                }
                get("/v1/models") {
                    val response = ModelsResponse(data = listOf(ModelInfo(id = modelId())))
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = jsonEngine.encodeToString(response)
                    )
                }
                get("/models") {
                    val response = ModelsResponse(data = listOf(ModelInfo(id = modelId())))
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = jsonEngine.encodeToString(response)
                    )
                }
                post("/v1/chat/completions") { handleChatCompletions(call) }
                post("/chat/completions") { handleChatCompletions(call) }
            }
        }.start(wait = false)
        AppLogger.d("HttpServer", "listening on $host:$port")
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        val started = System.currentTimeMillis()
        val id = "chatcmpl-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val mid = modelId()
        try {
            if (!busy.compareAndSet(false, true)) {
                call.respondText(
                    status = HttpStatusCode.TooManyRequests,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("Model is busy generating another response", type = "server_error", code = "busy"))
                    )
                )
                return
            }
            val text = try {
                call.receiveText()
            } catch (t: Throwable) {
                ""
            }
            if (text.isBlank()) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("Empty body — send JSON ChatCompletion request", type = "invalid_request_error"))
                    )
                )
                return
            }
            AppLogger.d("HttpServer", "chat/completions bytes=${text.length} preview=${text.take(180)}")
            val req = try {
                jsonEngine.decodeFromString<ChatCompletionRequest>(text)
            } catch (t: Throwable) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("Invalid JSON: ${t.message}", type = "invalid_request_error"))
                    )
                )
                return
            }
            val (userPrompt, systemPrompt) = splitMessages(req.messages)
            if (userPrompt.isBlank() && systemPrompt.isBlank()) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("messages[] is empty", type = "invalid_request_error"))
                    )
                )
                return
            }
            val modelName = req.model.ifBlank { mid }
            val flow = generate(userPrompt, systemPrompt)

            if (req.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                    // initial role chunk
                    writeSse(
                        jsonEngine.encodeToString(
                            ChatCompletionChunk(
                                id = id,
                                model = modelName,
                                choices = listOf(
                                    ChatStreamChoice(delta = ChatStreamDelta(role = "assistant", content = ""))
                                )
                            )
                        )
                    )
                    var prev = ""
                    try {
                        flow.collect { snapshot ->
                            val full = snapshot
                            val delta = if (full.startsWith(prev)) {
                                full.substring(prev.length)
                            } else {
                                // snapshot reset / non-prefix update — send full as delta once
                                full
                            }
                            prev = full
                            if (delta.isEmpty()) return@collect
                            writeSse(
                                jsonEngine.encodeToString(
                                    ChatCompletionChunk(
                                        id = id,
                                        model = modelName,
                                        choices = listOf(
                                            ChatStreamChoice(delta = ChatStreamDelta(content = delta))
                                        )
                                    )
                                )
                            )
                            flush()
                        }
                        writeSse(
                            jsonEngine.encodeToString(
                                ChatCompletionChunk(
                                    id = id,
                                    model = modelName,
                                    choices = listOf(
                                        ChatStreamChoice(
                                            delta = ChatStreamDelta(),
                                            finish_reason = "stop"
                                        )
                                    )
                                )
                            )
                        )
                        writeFully("data: [DONE]\n\n".toByteArray())
                        flush()
                    } catch (t: Throwable) {
                        AppLogger.e("HttpServer", "stream gen failed: ${t.message}", t)
                        writeSse(
                            jsonEngine.encodeToString(
                                ErrorBody(ErrorDetail(t.message ?: "generation failed"))
                            )
                        )
                    }
                }
            } else {
                // Non-stream: last snapshot wins (emissions are full cleaned text, not deltas)
                var last = ""
                flow.collect { snap -> last = snap }
                val response = ChatCompletionResponse(
                    id = id,
                    model = modelName,
                    choices = listOf(ChatChoice(message = ChatMessage("assistant", last)))
                )
                call.respondText(
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(response)
                )
            }
            AppLogger.d(
                "HttpServer",
                "done id=$id stream=${req.stream} ms=${System.currentTimeMillis() - started}"
            )
        } catch (t: Throwable) {
            AppLogger.e("HttpServer", "chat/completions error: ${t.message}", t)
            try {
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail(t.message ?: t.javaClass.simpleName))
                    )
                )
            } catch (_: Throwable) {
            }
        } finally {
            busy.set(false)
        }
    }

    private suspend fun ByteWriteChannel.writeSse(payload: String) {
        writeFully("data: $payload\n\n".toByteArray())
    }

    fun stop() {
        try {
            server?.stop(500, 1500)
        } catch (t: Throwable) {
            AppLogger.e("HttpServer", "stop: ${t.message}", t)
        }
        server = null
        busy.set(false)
    }

    companion object {
        /**
         * OpenAI-style messages → engine (user + system) for ChatML.
         * system roles become systemPrompt; user/assistant turns become dialogue text.
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
