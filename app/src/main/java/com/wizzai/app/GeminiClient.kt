package com.wizzai.app

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun ask(
        context: Context,
        prompt: String,
        onChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {

        val preferences = context.getSharedPreferences("wizz_settings", Context.MODE_PRIVATE)
        val apiKey = preferences.getString("gemini_key", "")?.trim().orEmpty()

        require(apiKey.isNotBlank()) {
            "API Key Gemini belum diisi."
        }

        val requestJson = JsonObject().apply {
            add("contents", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    add("parts", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", prompt)
                        })
                    })
                })
            })

            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.7)
                addProperty("maxOutputTokens", 2048)
            })
        }

        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-2.5-flash:streamGenerateContent?alt=sse"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(
                requestJson.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .build()

        val output = StringBuilder()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                val error = response.body?.string().orEmpty()
                throw Exception("Gemini Error ${response.code}: $error")
            }

            val reader = response.body?.charStream()?.buffered()
                ?: throw Exception("Respons Gemini kosong.")

            while (true) {
                val line = reader.readLine() ?: break

                if (!line.startsWith("data:")) continue

                val jsonText = line.removePrefix("data:").trim()
                if (jsonText.isBlank() || jsonText == "[DONE]") continue

                try {
                    val json = JsonParser.parseString(jsonText).asJsonObject
                    val candidates = json.getAsJsonArray("candidates")

                    if (candidates != null && candidates.size() > 0) {
                        val content = candidates[0].asJsonObject
                            .getAsJsonObject("content")

                        val parts = content?.getAsJsonArray("parts")

                        if (parts != null) {
                            for (part in parts) {
                                val text = part.asJsonObject
                                    .get("text")?.asString.orEmpty()

                                if (text.isNotEmpty()) {
                                    output.append(text)
                                    withContext(Dispatchers.Main) {
                                        onChunk(output.toString())
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Abaikan potongan SSE yang bukan JSON konten.
                }
            }
        }

        if (output.isEmpty()) {
            throw Exception("Gemini tidak memberikan jawaban.")
        }

        output.toString()
    }
}
