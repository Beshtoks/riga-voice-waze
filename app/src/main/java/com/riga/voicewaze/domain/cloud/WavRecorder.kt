package com.riga.voicewaze.domain.cloud

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavRecorder(
    private val cacheDir: File
) {
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var isRecording = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var currentFile: File? = null
    private var dataLengthBytes: Long = 0

    @Synchronized
    fun start(): File {
        check(!isRecording) { "Запись уже идёт" }

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBufferSize > 0) { "Не удалось получить буфер аудио" }

        val file = File(cacheDir, "lv_cloud_${System.currentTimeMillis()}.wav")
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Не удалось инициализировать микрофон")
        }

        audioRecord = recorder
        currentFile = file
        dataLengthBytes = 0
        isRecording = true

        recorder.startRecording()

        recordingThread = Thread {
            RandomAccessFile(file, "rw").use { raf ->
                raf.write(ByteArray(44))
                val buffer = ByteArray(minBufferSize)

                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        raf.write(buffer, 0, read)
                        dataLengthBytes += read
                    }
                }

                raf.seek(0)
                raf.write(createWavHeader(dataLengthBytes))
            }
        }.apply {
            name = "LatvianWavRecorderThread"
            start()
        }

        return file
    }

    @Synchronized
    fun stop(): File {
        check(isRecording) { "Запись не запущена" }

        val recorder = audioRecord ?: throw IllegalStateException("AudioRecord отсутствует")
        val file = currentFile ?: throw IllegalStateException("Файл записи отсутствует")

        isRecording = false

        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }

        recordingThread?.join(2000)

        recorder.release()
        audioRecord = null
        recordingThread = null
        currentFile = null

        return file
    }

    private fun createWavHeader(audioDataLength: Long): ByteArray {
        val totalDataLen = audioDataLength + 36
        val byteRate = sampleRate * 2

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(audioDataLength.toInt())
        }.array()
    }
}