package com.voicenotes.poc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voicenotes.poc.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recorder: AudioRecorder
    private lateinit var gemma: GemmaEngine

    private var currentPcmFile: File? = null
    private var isModelReady = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initModel() else status("⛔ Mic permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorder = AudioRecorder(this)
        gemma = GemmaEngine(this)

        setupRecordButton()
        checkPermissionAndInit()
    }

    private fun checkPermissionAndInit() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> initModel()
            else -> requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initModel() {
        lifecycleScope.launch {
            modelStatus("⏳ Loading Gemma E2B...")
            val result = withContext(Dispatchers.IO) { gemma.load() }
            result.fold(
                onSuccess = {
                    isModelReady = true
                    modelStatus("✅ Model ready — ${gemma.modelFile.length() / 1_000_000}MB")
                    binding.btnRecord.isEnabled = true
                },
                onFailure = {
                    modelStatus("❌ ${it.message}\n\nPlace model at:\n${gemma.modelFile.absolutePath}")
                }
            )
        }
    }

    private fun setupRecordButton() {
        binding.btnRecord.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isModelReady) startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isModelReady) stopAndProcess()
                    true
                }
                else -> false
            }
        }
    }

    private fun startRecording() {
        binding.tvTranscript.text = ""
        binding.tvJson.text = ""
        status("🔴 Recording...")
        binding.tvLatency.text = ""
        currentPcmFile = recorder.startRecording()
    }

    private fun stopAndProcess() {
        recorder.stopRecording()
        val pcm = currentPcmFile ?: return
        status("⏳ Processing...")
        binding.btnRecord.isEnabled = false

        lifecycleScope.launch {
            val wavFile = withContext(Dispatchers.IO) { recorder.pcmToWav(pcm) }

            var tokenCount = 0
            val result = withContext(Dispatchers.IO) {
                gemma.processAudio(wavFile) { token ->
                    tokenCount++
                    // Update transcript progressively on main thread
                    launch(Dispatchers.Main) {
                        binding.tvTranscript.append(token)
                    }
                }
            }

            wavFile.delete()
            binding.btnRecord.isEnabled = true

            result.fold(
                onSuccess = { note ->
                    binding.tvTranscript.text = note.transcript.ifEmpty { "(empty transcript)" }
                    binding.tvJson.text = formatResult(note)
                    status("✅ Done — intent: ${note.intent}")
                    binding.tvLatency.text = "⏱ ${note.latencyMs}ms | ~${tokenCount} tokens"
                },
                onFailure = {
                    status("❌ Error: ${it.message}")
                }
            )
        }
    }

    private fun formatResult(note: NoteResult) = buildString {
        appendLine("SUMMARY: ${note.summary}")
        appendLine("TAGS: ${note.tags.joinToString(", ")}")
        appendLine("TOPICS: ${note.topics.joinToString(", ")}")
        appendLine("INTENT: ${note.intent}")
        appendLine()
        appendLine("--- raw JSON ---")
        append(note.rawJson)
    }

    private fun status(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun modelStatus(msg: String) {
        binding.tvModelStatus.text = msg
    }

    override fun onDestroy() {
        super.onDestroy()
        gemma.unload()
    }
}
