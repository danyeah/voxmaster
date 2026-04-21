# VoiceNotes PoC — Android

Test pipeline: mic → Gemma 4 E2B → transcript + tags + intent

## Setup

1. **Download the model** (Gemma 4 E2B INT4, ~1.5GB):
   - From HuggingFace: `google/gemma-4-e2b-it` (MediaPipe format)
   - Push to device: `adb push gemma4-e2b-it-int4.bin /data/data/com.voicenotes.poc/files/`

2. **Build & install**:
   ```bash
   ./gradlew installDebug
   ```

3. **Run**: Hold the record button → speak → release → wait for Gemma output

## What it measures

- Cold model load time
- Inference latency (ms) per recording
- Quality of transcript + tag extraction in Italian/English
- Intent detection accuracy

## Files

- `AudioRecorder.kt` — 16kHz PCM capture + WAV conversion
- `GemmaEngine.kt` — MediaPipe LlmInference wrapper + JSON parser
- `MainActivity.kt` — hold-to-record UI, shows raw output

## Note on audio modality

MediaPipe GenAI Tasks multimodal audio API support depends on the SDK version.
If audio input isn't available yet, the engine falls back to text-only inference
(describe the audio path in the prompt). Check MediaPipe release notes for `tasks-genai >= 0.10.22`.
