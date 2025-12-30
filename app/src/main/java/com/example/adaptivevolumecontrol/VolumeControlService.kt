package com.example.adaptivevolumecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.log10

class VolumeControlService : Service() {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    // Настройки записи звука
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    // Параметры управления громкостью
    private val checkInterval = 5000L // Проверка каждые 500мс
    private var baseVolume = 10 // Базовая громкость
    private val minVolume = 5
    private val maxVolume = 15

    // Пороги шума (в децибелах)
    private val lowNoiseThreshold = 50.0 // Тихо (машина стоит)
    private val highNoiseThreshold = 70.0 // Громко (машина едет)

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        createNotificationChannel()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startNoiseMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNoiseMonitoring() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            isRecording = true
            audioRecord?.startRecording()

            // Запускаем периодическую проверку уровня шума
            handler.post(noiseCheckRunnable)

        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private val noiseCheckRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val noiseLevel = getCurrentNoiseLevel()
                adjustVolume(noiseLevel)
                handler.postDelayed(this, checkInterval)
            }
        }
    }

    private fun getCurrentNoiseLevel(): Double {
        val buffer = ShortArray(bufferSize)
        val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0

        if (readSize > 0) {
            // Вычисляем среднюю амплитуду
            var sum = 0.0
            for (i in 0 until readSize) {
                sum += abs(buffer[i].toDouble())
            }
            val average = sum / readSize

            // Преобразуем в децибелы
            return if (average > 0) {
                20 * log10(average)
            } else {
                0.0
            }
        }
        return 0.0
    }

    private fun adjustVolume(noiseLevel: Double) {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val targetVolume = when {
            noiseLevel < lowNoiseThreshold -> {
                // Тихо - уменьшаем громкость
                minVolume.coerceAtMost(maxSystemVolume)
            }
            noiseLevel > highNoiseThreshold -> {
                // Громко - увеличиваем громкость
                maxVolume.coerceAtMost(maxSystemVolume)
            }
            else -> {
                // Средний уровень - интерполируем
                val ratio = (noiseLevel - lowNoiseThreshold) / (highNoiseThreshold - lowNoiseThreshold)
                val volume = (minVolume + (maxVolume - minVolume) * ratio).toInt()
                volume.coerceAtMost(maxSystemVolume)
            }
        }

        // Плавное изменение громкости (по 1 шагу за раз)
        if (currentVolume < targetVolume) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                currentVolume + 1,
                0
            )
        } else if (currentVolume > targetVolume) {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                currentVolume - 1,
                0
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Adaptive Volume Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Автоматическая регулировка громкости"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Adaptive Volume")
            .setContentText("Мониторинг шума активен")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        handler.removeCallbacks(noiseCheckRunnable)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    companion object {
        private const val CHANNEL_ID = "VolumeControlChannel"
    }
}