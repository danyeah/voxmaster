package com.voicenotes.poc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun startRecording(): File? {
        if (!hasPermission) return null

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 4
        )

        val outFile = File(context.cacheDir, "recording_${System.currentTimeMillis()}.pcm")
        isRecording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            FileOutputStream(outFile).use { fos ->
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) fos.write(buffer, 0, read)
                }
            }
        }.start()

        return outFile
    }

    fun stopRecording(): Long {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        return 0L
    }

    // Convert raw PCM 16-bit to WAV for MediaPipe
    fun pcmToWav(pcmFile: File): File {
        val wavFile = File(pcmFile.parent, pcmFile.nameWithoutExtension + ".wav")
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 36

        FileOutputStream(wavFile).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(totalDataLen)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)           // PCM chunk size
                putShort(1)          // PCM format
                putShort(1)          // mono
                putInt(SAMPLE_RATE)
                putInt(SAMPLE_RATE * 2) // byte rate
                putShort(2)          // block align
                putShort(16)         // bits per sample
                put("data".toByteArray())
                putInt(pcmData.size)
            }
            out.write(header.array())
            out.write(pcmData)
        }

        pcmFile.delete()
        return wavFile
    }
}
