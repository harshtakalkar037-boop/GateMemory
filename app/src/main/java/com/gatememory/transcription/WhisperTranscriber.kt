package com.gatememory.transcription

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig

class WhisperTranscriber(private val context: Context) {
    suspend fun transcribe(audioPath: String): String? {
        val model = Whisper.loadModelFromAsset(context, MODEL_ASSET)
        return try {
            Whisper.transcribe(
                model = model,
                audioPath = audioPath,
                config = WhisperConfig(language = "en"),
            ).text.trim().takeIf { it.isNotBlank() }
        } finally {
            Whisper.releaseModel(model)
        }
    }

    companion object {
        private const val MODEL_ASSET = "models/ggml-tiny.en.bin"
    }
}
