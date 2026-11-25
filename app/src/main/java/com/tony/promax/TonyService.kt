package com.tony.promax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TonyService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isAwake = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ur", "PK"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("hi", "IN"))
            }
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "TonyUtterance")
    }

    private fun setupSpeechRecognizer() {
        mainHandler.post {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    restartListening()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull()
                    if (!spokenText.isNullOrEmpty()) {
                        processVoiceCommand(spokenText.lowercase(Locale.getDefault()))
                    }
                    restartListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            restartListening()
        }
    }

    private fun restartListening() {
        mainHandler.postDelayed({
            startListening()
        }, 700)
    }

    private fun processVoiceCommand(cmd: String) {
        if (cmd.contains("tony") || cmd.contains("ٹونی") || cmd.contains("टोनी")) {
            isAwake = true
            speak("جی، حکم کریں؟")
            return
        }

        if (isAwake) {
            isAwake = false
            when {
                cmd.contains("ٹارچ") || cmd.contains("فلیش") || cmd.contains("torch") -> {
                    val turnOn = cmd.contains("آن") || cmd.contains("جلا") || cmd.contains("on")
                    toggleFlashlight(turnOn)
                }
                cmd.contains("واٹس ایپ") || cmd.contains("whatsapp") -> {
                    openApp("com.whatsapp", "واٹس ایپ کھول رہا ہوں")
                }
                cmd.contains("یوٹیوب") || cmd.contains("youtube") -> {
                    openApp("com.google.android.youtube", "یوٹیوب کھول رہا ہوں")
                }
                cmd.contains("ٹائم") || cmd.contains("وقت") || cmd.contains("time") -> {
                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    speak("اس وقت $time ہوئے ہیں")
                }
                else -> {
                    speak("آپ نے کہا: $cmd")
                }
            }
        }
    }

    private fun toggleFlashlight(state: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, state)
                speak(if (state) "ٹارچ آن کر دی ہے" else "ٹارچ بند کر دی ہے")
            }
        } catch (e: Exception) {
            speak("معذرت، ٹارچ کنٹرول نہیں ہو سکی")
        }
    }

    private fun openApp(packageName: String, reply: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak(reply)
        } else {
            speak("یہ ایپ آپ کے فون میں انسٹال نہیں ہے")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, "TonyChannel")
            .setContentTitle("Tony Pro Max (Offline Ready)")
            .setContentText("کہیں 'ٹونی' اور پھر اپنا حکم دیں...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "TonyChannel",
                "Tony Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
