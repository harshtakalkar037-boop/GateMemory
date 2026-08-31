package com.gatememory.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class WavAudioRecorder {
    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    fun start(file: File) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = minBufferSize.coerceAtLeast(SAMPLE_RATE / 2)

        @Suppress("MissingPermission")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        audioRecord = recorder
        isRecording.set(true)

        recordingThread = Thread {
            writeWav(file, recorder, bufferSize)
        }.apply { start() }
    }

    fun stop() {
        isRecording.set(false)
        audioRecord?.stop()
        recordingThread?.join(1_000L)
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
    }

    fun release() {
        if (isRecording.get()) {
            stop()
        } else {
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun writeWav(file: File, recorder: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        RandomAccessFile(file, "rw").use { wav ->
            wav.setLength(0)
            wav.write(ByteArray(WAV_HEADER_SIZE))
            recorder.startRecording()

            var pcmBytes = 0
            while (isRecording.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    wav.write(buffer, 0, read)
                    pcmBytes += read
                }
            }

            wav.seek(0)
            wav.write(wavHeader(pcmBytes))
        }
    }

    private fun wavHeader(pcmBytes: Int): ByteArray {
        val totalDataLen = pcmBytes + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        return byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            (totalDataLen and 0xff).toByte(), ((totalDataLen shr 8) and 0xff).toByte(),
            ((totalDataLen shr 16) and 0xff).toByte(), ((totalDataLen shr 24) and 0xff).toByte(),
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            'f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(),
            16, 0, 0, 0,
            1, 0,
            CHANNELS.toByte(), 0,
            (SAMPLE_RATE and 0xff).toByte(), ((SAMPLE_RATE shr 8) and 0xff).toByte(),
            ((SAMPLE_RATE shr 16) and 0xff).toByte(), ((SAMPLE_RATE shr 24) and 0xff).toByte(),
            (byteRate and 0xff).toByte(), ((byteRate shr 8) and 0xff).toByte(),
            ((byteRate shr 16) and 0xff).toByte(), ((byteRate shr 24) and 0xff).toByte(),
            (CHANNELS * BITS_PER_SAMPLE / 8).toByte(), 0,
            BITS_PER_SAMPLE.toByte(), 0,
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            (pcmBytes and 0xff).toByte(), ((pcmBytes shr 8) and 0xff).toByte(),
            ((pcmBytes shr 16) and 0xff).toByte(), ((pcmBytes shr 24) and 0xff).toByte(),
        )
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
    }
}
