package com.tony.promax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class TonyService : Service(), TextToSpeech.OnInitListener, RecognitionListener {

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var tts: TextToSpeech? = null
    private var isAwake = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        initOfflineVoiceEngine()
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

    private fun updateNotification(status: String) {
        val notification: Notification = NotificationCompat.Builder(this, "TonyChannel")
            .setContentTitle("Tony Pro Max (Offline Engine)")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    private fun initOfflineVoiceEngine() {
        Thread {
            try {
                val modelDir = File(filesDir, "vosk-model")
                if (!modelDir.exists() || modelDir.listFiles().isNullOrEmpty()) {
                    updateNotification("آف لائن ماڈل ڈاؤن لوڈ ہو رہا ہے (42 MB)...")
                    speak("پہلی بار آف لائن وائس ماڈل ڈاؤن لوڈ کر رہا ہوں، براہ کرم انتظار کریں")
                    downloadAndUnzipModel(modelDir)
                }
                updateNotification("ماڈل لوڈ ہو رہا ہے...")
                model = Model(modelDir.absolutePath)
                val recognizer = Recognizer(model, 16000.0f)
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
                updateNotification("🟢 آف لائن سن رہا ہے: کہیں 'ٹونی'")
                speak("ٹونی مکمل طور پر تیار ہے")
            } catch (e: Exception) {
                updateNotification("ماڈل ایرر: انٹرنیٹ چیک کریں")
                speak("آف لائن ماڈل ڈاؤن لوڈ نہیں ہو سکا، انٹرنیٹ چیک کریں")
            }
        }.start()
    }

    private fun downloadAndUnzipModel(targetDir: File) {
        targetDir.mkdirs()
        val zipFile = File(cacheDir, "model.zip")
        URL("https://://alphacephei.com").openStream().use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val nameParts = entry.name.split("/")
                if (nameParts.size > 1 && nameParts[1].isNotEmpty()) {
                    val relPath = nameParts.drop(1).joinToString("/")
                    val outFile = File(targetDir, relPath)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        zipFile.delete()
    }

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrEmpty()) return
        try {
            val text = JSONObject(hypothesis).optString("text", "").trim()
            if (text.isNotEmpty()) {
                processVoiceCommand(text)
            }
        } catch (_: Exception) {}
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {}
    override fun onTimeout() {}

    private fun processVoiceCommand(cmd: String) {
        if (cmd.contains("टोनी") || cmd.contains("टॉनी") || cmd.contains("tony") || cmd.contains("सुनो")) {
            isAwake = true
            speak("جی، حکم کریں؟")
            return
        }

        if (isAwake) {
            isAwake = false
            when {
                cmd.contains("टॉर्च") || cmd.contains("लाइट") || cmd.contains("फ्लैश") -> {
                    val turnOn = !cmd.contains("बंद") && !cmd.contains("ऑफ")
                    toggleFlashlight(turnOn)
                }
                cmd.contains("व्हाट्सएप") || cmd.contains("वाट्सएप") -> {
                    openApp("com.whatsapp", "واٹس ایپ کھول رہا ہوں")
                }
                cmd.contains("यूट्यूब") -> {
                    openApp("com.google.android.youtube", "یوٹیوب کھول رہا ہوں")
                }
                cmd.contains("टाइम") || cmd.contains("समय") || cmd.contains("वक्त") -> {
                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    speak("اس وقت  ہوئے ہیں")
                }
                else -> {
                    speak("آپ نے کہا: ")
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
            .setContentTitle("Tony Pro Max")
            .setContentText("شروع ہو رہا ہے...")
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
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
