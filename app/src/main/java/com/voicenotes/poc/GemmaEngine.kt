package com.voicenotes.poc

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import java.io.File

data class NoteResult(
    val transcript: String,
    val summary: String,
    val tags: List<String>,
    val topics: List<String>,
    val intent: String,
    val rawJson: String,
    val latencyMs: Long,
)

class GemmaEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaEngine"
        // Model stored in app's files dir. User must place it there or use ModelDownloader.
        const val MODEL_FILENAME = "gemma4-e2b-it-int4.bin"
    }

    private var llm: LlmInference? = null

    val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)

    val isModelReady: Boolean
        get() = modelFile.exists() && modelFile.length() > 0

    fun load(): Result<Unit> = runCatching {
        check(isModelReady) { "Model not found at ${modelFile.absolutePath}" }

        val options = LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(1024)
            .setMaxTopK(40)
            .setTemperature(0.1f) // low temp for structured output
            .build()

        llm = LlmInference.createFromOptions(context, options)
        Log.i(TAG, "Gemma loaded: ${modelFile.name} (${modelFile.length() / 1_000_000}MB)")
    }

    fun processAudio(wavFile: File, onToken: (String) -> Unit = {}): Result<NoteResult> = runCatching {
        val engine = checkNotNull(llm) { "Engine not loaded" }

        val prompt = buildPrompt(wavFile)
        val startMs = System.currentTimeMillis()

        val sb = StringBuilder()
        // MediaPipe streaming response
        engine.generateResponseAsync(prompt) { partial, done ->
            if (partial != null) {
                sb.append(partial)
                onToken(partial)
            }
        }

        // Block until done (simple approach for PoC)
        val rawJson = waitForCompletion(engine, prompt, sb)
        val latencyMs = System.currentTimeMillis() - startMs

        Log.d(TAG, "Raw output (${latencyMs}ms):\n$rawJson")
        parseOutput(rawJson, latencyMs)
    }

    private fun buildPrompt(wavFile: File): String {
        // In the actual MediaPipe multimodal API, audio is passed as a media file reference.
        // For PoC we use the text-only path with a transcription request.
        // When MediaPipe audio is available, replace with: "<audio>${wavFile.absolutePath}</audio>"
        return """<start_of_turn>user
You are a voice note assistant. Listen to this audio recording and respond ONLY with a JSON object, no prose.

Audio file: ${wavFile.absolutePath}

Respond with this exact JSON structure:
{
  "transcript": "full verbatim transcription",
  "summary": "1-2 sentence summary",
  "tags": ["tag1", "tag2"],
  "topics": ["topic1", "topic2"],
  "intent": "note_only"
}

Intents: note_only | create_event | send_email | set_reminder
JSON only, no markdown, no explanation.
<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    private fun waitForCompletion(engine: LlmInference, prompt: String, sb: StringBuilder): String {
        // Synchronous fallback for PoC — generateResponse blocks
        return try {
            engine.generateResponse(prompt)
        } catch (e: Exception) {
            sb.toString().ifEmpty { throw e }
        }
    }

    private fun parseOutput(raw: String, latencyMs: Long): NoteResult {
        // Extract JSON even if model added prose around it
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        val json = if (jsonStart >= 0 && jsonEnd > jsonStart) raw.substring(jsonStart, jsonEnd + 1) else raw

        return try {
            // Manual parse (no gson dep in PoC)
            NoteResult(
                transcript = extractString(json, "transcript"),
                summary = extractString(json, "summary"),
                tags = extractArray(json, "tags"),
                topics = extractArray(json, "topics"),
                intent = extractString(json, "intent"),
                rawJson = json,
                latencyMs = latencyMs,
            )
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed, returning raw: $e")
            NoteResult(
                transcript = raw,
                summary = "",
                tags = emptyList(),
                topics = emptyList(),
                intent = "note_only",
                rawJson = raw,
                latencyMs = latencyMs,
            )
        }
    }

    private fun extractString(json: String, key: String): String {
        val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return regex.find(json)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
    }

    private fun extractArray(json: String, key: String): List<String> {
        val regex = Regex(""""$key"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val content = regex.find(json)?.groupValues?.get(1) ?: return emptyList()
        return Regex(""""([^"]+)"""").findAll(content).map { it.groupValues[1] }.toList()
    }

    fun unload() {
        llm?.close()
        llm = null
    }
}
