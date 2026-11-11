package com.compass.diary.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

sealed interface GeminiResult {
    data class Text(val text: String, val grounded: Boolean, val sources: List<String>) : GeminiResult
    data class Failed(val reason: String) : GeminiResult
    object Offline : GeminiResult
    /** [daily] = the day's free quota is used up (waiting a few seconds will not help). */
    data class RateLimited(val daily: Boolean) : GeminiResult
    object BadKey : GeminiResult
}

data class GeminiRequest(
    /** Each part is {"text": ...} or {"file_data": {"file_uri": ...}}. */
    val parts: List<JSONObject>,
    val json: Boolean = false,
    val schema: JSONObject? = null,
    /** Let Gemini search Google while answering (cannot be combined with [json]). */
    val grounded: Boolean = false,
    val lowRes: Boolean = false,
    val thinking: Boolean = true
)

/** The one place that talks to Gemini; faked in tests. */
interface GeminiApi {
    suspend fun generate(request: GeminiRequest): GeminiResult
}

/** Pure helpers for reading Gemini's HTTP answers. */
object GeminiJson {
    fun textOf(body: String): String? = try {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null) null else (0 until parts.length())
            .mapNotNull { i -> parts.optJSONObject(i)?.takeIf { !it.optBoolean("thought", false) }?.optString("text")?.takeIf { it.isNotBlank() } }
            .joinToString("")
            .ifBlank { null }
    } catch (e: Exception) {
        null
    }

    /** Titles of the web pages Gemini consulted (empty when it did not search). */
    fun sourcesOf(body: String): List<String> = try {
        val chunks = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
        if (chunks == null) emptyList() else (0 until chunks.length())
            .mapNotNull { chunks.optJSONObject(it)?.optJSONObject("web")?.optString("title")?.takeIf { t -> t.isNotBlank() } }
            .distinct().take(8)
    } catch (e: Exception) {
        emptyList()
    }

    fun errorMessage(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message", "") ?: body.take(200)
    } catch (e: Exception) {
        body.take(200)
    }

    /** True when a 429 means "today's free quota is gone" rather than "slow down for a few seconds". */
    fun isDailyQuota(body: String): Boolean =
        body.contains("PerDay", ignoreCase = true) || body.contains("per day", ignoreCase = true)

    /** "retryDelay":"23s" inside a 429 error -> 23000 (clamped to something sensible). */
    fun retryDelayMs(errorBody: String): Long {
        val fallback = 20_000L
        return try {
            val details = JSONObject(errorBody).optJSONObject("error")?.optJSONArray("details") ?: return fallback
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i)?.optString("retryDelay", "") ?: ""
                val secs = d.removeSuffix("s").toDoubleOrNull()
                if (secs != null) return (secs * 1000).toLong().coerceIn(10_000L, 90_000L)
            }
            fallback
        } catch (e: Exception) {
            fallback
        }
    }
}

class HttpGeminiApi(
    private val key: String,
    private val http: OkHttpClient,
    private val models: List<String>,
    private val onWait: (String?) -> Unit = {}
) : GeminiApi {

    private data class Features(val thinking: Boolean, val schema: Boolean, val lowRes: Boolean, val grounded: Boolean) {
        /** Optional extras are switched off one at a time if the API rejects the request (400). */
        fun dropNext(): Features? = when {
            thinking -> copy(thinking = false)
            schema -> copy(schema = false)
            lowRes -> copy(lowRes = false)
            grounded -> copy(grounded = false)
            else -> null
        }
    }

    private sealed interface Call {
        data class Ok(val body: String) : Call
        data class Limited(val waitMs: Long, val daily: Boolean) : Call
        object Overloaded : Call
        data class Rejected(val code: Int, val message: String) : Call
        object Offline : Call
    }

    override suspend fun generate(request: GeminiRequest): GeminiResult {
        var features = Features(request.thinking, request.schema != null, request.lowRes, request.grounded)
        var modelIdx = 0
        var limitedTries = 0
        var busyTries = 0
        while (true) {
            val model = models.getOrNull(modelIdx) ?: return GeminiResult.Failed("no Gemini model accepted the request")
            when (val r = call(model, body(request, features))) {
                is Call.Ok -> {
                    val text = GeminiJson.textOf(r.body) ?: return GeminiResult.Failed("empty or blocked answer")
                    val sources = if (features.grounded) GeminiJson.sourcesOf(r.body) else emptyList()
                    return GeminiResult.Text(text, grounded = features.grounded && sources.isNotEmpty(), sources = sources)
                }
                is Call.Limited -> {
                    if (r.daily) return GeminiResult.RateLimited(daily = true)
                    if (++limitedTries > MAX_RATE_RETRIES) return GeminiResult.RateLimited(daily = false)
                    onWait("Waiting for Gemini's rate limit…")
                    delay(r.waitMs)
                    onWait(null)
                }
                Call.Overloaded -> {
                    if (++busyTries > 3) { modelIdx++; busyTries = 0 }
                    delay(4_000L)
                }
                Call.Offline -> return GeminiResult.Offline
                is Call.Rejected -> when {
                    r.message.contains("API key", ignoreCase = true) || r.code == 403 -> return GeminiResult.BadKey
                    r.code == 400 -> {
                        val fewer = features.dropNext()
                        if (fewer != null) features = fewer
                        else if (modelIdx + 1 < models.size) modelIdx++
                        else return GeminiResult.Failed("HTTP 400: ${r.message}")
                    }
                    r.code == 404 && modelIdx + 1 < models.size -> modelIdx++
                    else -> return GeminiResult.Failed("HTTP ${r.code}: ${r.message}")
                }
            }
        }
    }

    private fun body(request: GeminiRequest, f: Features): JSONObject {
        val config = JSONObject().put("temperature", 0.1)
        if (request.json) config.put("responseMimeType", "application/json")
        if (request.json && f.schema && request.schema != null) config.put("responseSchema", request.schema)
        if (f.lowRes) config.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
        if (f.thinking) config.put("thinkingConfig", JSONObject().put("thinkingBudget", THINKING_BUDGET))
        val parts = JSONArray().also { a -> request.parts.forEach { a.put(it) } }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
            // Song lyrics can be bold; the sorter must still be able to read them.
            .put("safetySettings", JSONArray().also { a ->
                listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
                    .forEach { a.put(JSONObject().put("category", it).put("threshold", "BLOCK_ONLY_HIGH")) }
            })
        if (f.grounded) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return body
    }

    private suspend fun call(model: String, body: JSONObject): Call = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", key)
                .addHeader("content-type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.isSuccessful -> Call.Ok(text)
                    resp.code == 429 -> Call.Limited(GeminiJson.retryDelayMs(text), GeminiJson.isDailyQuota(text))
                    resp.code == 503 || text.contains("overloaded", ignoreCase = true) -> Call.Overloaded
                    else -> Call.Rejected(resp.code, GeminiJson.errorMessage(text))
                }
            }
        } catch (e: IOException) {
            Call.Offline
        }
    }

    private companion object {
        const val MAX_RATE_RETRIES = 4
        const val THINKING_BUDGET = 8192
    }
}
