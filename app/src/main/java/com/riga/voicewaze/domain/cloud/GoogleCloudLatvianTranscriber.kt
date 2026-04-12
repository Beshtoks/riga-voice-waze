package com.riga.voicewaze.domain.cloud

import android.util.Base64
import com.riga.voicewaze.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GoogleCloudLatvianTranscriber {

    fun transcribe(wavFile: File): String {
        val apiKey = BuildConfig.GOOGLE_CLOUD_STT_API_KEY.trim()
        require(apiKey.isNotBlank()) {
            "Не задан Google Cloud Speech API key в local.properties (GOOGLE_CLOUD_STT_API_KEY)"
        }

        val audioBase64 = Base64.encodeToString(wavFile.readBytes(), Base64.NO_WRAP)

        val body = JSONObject().apply {
            put(
                "config",
                JSONObject().apply {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", 16000)
                    put("languageCode", "lv-LV")
                    put("maxAlternatives", 1)
                    put("enableAutomaticPunctuation", false)
                }
            )
            put(
                "audio",
                JSONObject().apply {
                    put("content", audioBase64)
                }
            )
        }.toString()

        val connection = (URL("https://speech.googleapis.com/v1/speech:recognize?key=$apiKey")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: "HTTP $responseCode"
            }

            if (responseCode !in 200..299) {
                throw IllegalStateException(parseGoogleError(responseText))
            }

            parseTranscript(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTranscript(json: String): String {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: return ""

        val parts = mutableListOf<String>()
        for (i in 0 until results.length()) {
            val result = results.optJSONObject(i) ?: continue
            val alternatives = result.optJSONArray("alternatives") ?: continue
            val first = alternatives.optJSONObject(0) ?: continue
            val transcript = first.optString("transcript").trim()
            if (transcript.isNotBlank()) {
                parts += transcript
            }
        }

        return parts.joinToString(" ").trim()
    }

    private fun parseGoogleError(json: String): String {
        return try {
            val root = JSONObject(json)
            val error = root.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "Ошибка Google Cloud Speech"
        } catch (_: Exception) {
            json.ifBlank { "Ошибка Google Cloud Speech" }
        }
    }
}
