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
    private val generate: suspend (userPrompt: String, systemPrompt: String, maxTokens: Int) -> Flow<String> =
        { _, _, _ -> emptyFlow() },
    private val nCtxHint: () -> Int = { 2048 },
    private val modelId: () -> String = { "unknown" },
    private val cancelGenerate: () -> Unit = {},
    private val defaultMaxTokens: () -> Int = { 256 }
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
                        """{"ok":true,"model":${jsonEngine.encodeToString(modelId())},"busy":${busy.get()},"n_ctx":${nCtxHint()}}""",
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
        var holdsBusy = false
        try {
            if (!busy.compareAndSet(false, true)) {
                AppLogger.d("HttpServer", "429 busy (not cancelling in-flight)")
                call.respondText(
                    status = HttpStatusCode.TooManyRequests,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("Model busy — wait or cancel prior request (long CPU prefill on big system prompts)", type = "server_error", code = "busy"))
                    )
                )
                return
            }
            holdsBusy = true
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
            val (userPrompt0, systemPrompt0) = splitMessages(req.messages)
            if (userPrompt0.isBlank() && systemPrompt0.isBlank()) {
                call.respondText(
                    status = HttpStatusCode.BadRequest,
                    contentType = ContentType.Application.Json,
                    text = jsonEngine.encodeToString(
                        ErrorBody(ErrorDetail("messages[] is empty", type = "invalid_request_error"))
                    )
                )
                return
            }
            val nctx = nCtxHint().coerceAtLeast(512)
            val charBudget = (nctx * 2).coerceAtLeast(1200) // ~2 chars/token; leave headroom for reply
            val (userPrompt, systemPrompt, wasTrimmed) = trimForBudget(userPrompt0, systemPrompt0, charBudget)
            if (wasTrimmed) {
                AppLogger.d(
                    "HttpServer",
                    "trimmed prompt ${userPrompt0.length + systemPrompt0.length}→${userPrompt.length + systemPrompt.length} chars for n_ctx=$nctx"
                )
            }
            val modelName = req.model.ifBlank { mid }
            val maxTok = if (req.max_tokens <= 0) defaultMaxTokens().coerceIn(16, 2048) else req.max_tokens.coerceIn(1, 2048)
            AppLogger.d(
                "HttpServer",
                "gen model=$modelName stream=${req.stream} maxTok=$maxTok sys=${systemPrompt.length} user=${userPrompt.length} msgs=${req.messages.size}"
            )
            val flow = generate(userPrompt, systemPrompt, maxTok)

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
                try {
                    flow.collect { snap -> last = snap }
                } catch (t: Throwable) {
                    AppLogger.e("HttpServer", "collect aborted: ${t.message}")
                    try { cancelGenerate() } catch (_: Throwable) {}
                    throw t
                }
                if (last.startsWith("ERROR:")) {
                    AppLogger.e("HttpServer", "gen $last")
                    call.respondText(
                        status = HttpStatusCode.BadRequest,
                        contentType = ContentType.Application.Json,
                        text = jsonEngine.encodeToString(
                            ErrorBody(
                                ErrorDetail(
                                    last.removePrefix("ERROR:").trim(),
                                    type = "invalid_request_error",
                                    code = "context_length"
                                )
                            )
                        )
                    )
                    return
                }
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
            if (holdsBusy) {
                try { cancelGenerate() } catch (_: Throwable) {}
                busy.set(false)
            }
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
        
        fun trimForBudget(user: String, system: String, budgetChars: Int): Triple<String, String, Boolean> {
            val budget = budgetChars.coerceAtLeast(800)
            val maxSys = (budget * 2 / 5).coerceAtLeast(500)
            val maxUser = (budget - maxSys - 64).coerceAtLeast(budget / 3)
            var sys = system
            var usr = user
            var trimmed = false
            if (sys.length > maxSys) {
                val head = (maxSys * 2 / 5).coerceAtLeast(180)
                val tail = (maxSys - head - 32).coerceAtLeast(180)
                sys = sys.take(head) + "\n…[system truncated for n_ctx]…\n" + sys.takeLast(tail)
                trimmed = true
            }
            if (usr.length > maxUser) {
                var start = (usr.length - maxUser).coerceAtLeast(0)
                val nl = usr.indexOf('\n', start)
                if (nl in start until (start + 200)) start = nl + 1
                usr = "…[earlier truncated]…\n" + usr.substring(start.coerceAtMost(usr.length))
                trimmed = true
            }
            return Triple(usr, sys, trimmed)
        }

        /**
         * Multi-turn: pack prior turns into system as ChatML history; last user = userPrompt.
         * Single user message stays plain.
         */
        fun splitMessages(messages: List<ChatMessage>): Pair<String, String> {
            val systems = ArrayList<String>()
            val turns = ArrayList<ChatMessage>()
            for (m in messages) {
                val role = m.role.lowercase()
                val content = m.content
                if (content.isBlank() && role != "assistant") continue
                when (role) {
                    "system", "developer" -> {
                        if (content.isNotBlank()) systems.add(content)
                    }
                    else -> turns.add(ChatMessage(role = role, content = content))
                }
            }
            val baseSystem = systems.joinToString("\n").trim()
            if (turns.isEmpty()) return "" to baseSystem
            if (turns.size == 1 && turns[0].role == "user") {
                return turns[0].content to baseSystem
            }
            var lastUserIdx = turns.indexOfLast { it.role == "user" }
            if (lastUserIdx < 0) lastUserIdx = turns.lastIndex
            val history = turns.subList(0, lastUserIdx)
            val last = turns[lastUserIdx]
            val hist = StringBuilder()
            if (baseSystem.isNotEmpty()) {
                hist.append(baseSystem.trim()).append("\n\n")
            }
            if (history.isNotEmpty()) {
                hist.append("## Conversation so far\n")
                for (t in history) {
                    val r = when (t.role) {
                        "assistant" -> "assistant"
                        "tool" -> "tool"
                        else -> "user"
                    }
                    hist.append("<|im_start|>").append(r).append("\n")
                    hist.append(t.content.trim())
                    hist.append("<|im_end|>\n")
                }
                hist.append("Continue as the assistant. Stay in character. /no_think")
            }
            val userContent = if (last.role == "user") last.content
            else turns.joinToString("\n") { "${it.role}: ${it.content}" }
            return userContent to hist.toString().trim()
        }


    }
}
