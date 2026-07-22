package com.example.offlinellm.data.remote

import com.example.offlinellm.data.local.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class HfModelHit(
    val repoId: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val pipelineTag: String = "",
)

data class HfGgufFile(
    val path: String,
    val sizeBytes: Long,
    val resolveUrl: String,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_000_000_000L -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000L -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            sizeBytes > 0L -> "%.0f KB".format(sizeBytes / 1_000.0)
            else -> "?"
        }
}

/** Hugging Face Hub: search GGUF repos + list .gguf files. */
object HfHubClient {
    private const val UA = "OfflineLLM/1.5 (Android; HF-search)"

    suspend fun searchGgufModels(
        query: String,
        token: String? = null,
        limit: Int = 25,
    ): List<HfModelHit> = withContext(Dispatchers.IO) {
        val q = query.trim().ifBlank { "GGUF" }
        val enc = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
        val url =
            "https://huggingface.co/api/models?search=$enc&filter=gguf&sort=downloads&direction=-1&limit=$limit"
        val body = httpGet(url, token)
        val arr = JSONArray(body)
        val out = ArrayList<HfModelHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("modelId") }
            if (id.isBlank()) continue
            out += HfModelHit(
                repoId = id,
                downloads = o.optInt("downloads", 0),
                likes = o.optInt("likes", 0),
                pipelineTag = o.optString("pipeline_tag", ""),
            )
        }
        AppLogger.d("HF", "search q='$q' hits=${out.size}")
        out
    }

    suspend fun listGgufFiles(
        repoId: String,
        token: String? = null,
    ): List<HfGgufFile> = withContext(Dispatchers.IO) {
        val repo = repoId.trim().trimStart('/')
        require(repo.isNotBlank() && repo.contains('/')) { "Bad repo id: $repoId" }
        val encRepo = repo.split('/').joinToString("/") {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name())
        }
        val url = "https://huggingface.co/api/models/$encRepo/tree/main?recursive=1"
        val body = httpGet(url, token)
        val arr = JSONArray(body)
        val out = ArrayList<HfGgufFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("path")
            if (!path.endsWith(".gguf", ignoreCase = true)) continue
            val lower = path.lowercase()
            if (lower.contains("mmproj") || lower.contains("imatrix") || lower.contains("projector")) {
                continue
            }
            val size = when {
                o.has("size") && !o.isNull("size") -> o.optLong("size", 0L)
                o.has("lfs") -> o.optJSONObject("lfs")?.optLong("size", 0L) ?: 0L
                else -> 0L
            }
            val resolve =
                "https://huggingface.co/$repo/resolve/main/" + path.split('/').joinToString("/") { seg ->
                    URLEncoder.encode(seg, StandardCharsets.UTF_8.name()).replace("+", "%20")
                }
            out += HfGgufFile(path = path, sizeBytes = size, resolveUrl = resolve)
        }
        val rank = { f: HfGgufFile ->
            val n = f.fileName.uppercase()
            when {
                "Q4_K_M" in n -> 0
                "Q4_K_S" in n -> 1
                "Q4_0" in n -> 2
                "IQ4" in n -> 3
                "Q3_K_M" in n -> 4
                "Q5_K_M" in n -> 5
                "Q8_0" in n -> 8
                "BF16" in n || "F16" in n -> 9
                else -> 6
            }
        }
        out.sortedWith(compareBy({ rank(it) }, { it.sizeBytes }, { it.path }))
            .also { AppLogger.d("HF", "list $repo gguf=${it.size}") }
    }

    private fun httpGet(url: String, token: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HF HTTP $code: ${text.take(200)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
