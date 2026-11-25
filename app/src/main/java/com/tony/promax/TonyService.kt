package com.tony.promax

import android.app.*
import android.content.*
import android.hardware.camera2.CameraManager
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.*
import org.vosk.android.*
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class TonyService : Service(), TextToSpeech.OnInitListener, RecognitionListener {
    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var tts: TextToSpeech? = null
    private var isAwake = false

    private fun getModelUrl(): String {
        val a = String(charArrayOf('h','t','t','p','s',':','/','/'))
        return a + "://alphacephei.com"
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        initEngine()
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ur", "PK"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale("hi", "IN"))
        }
    }

    private fun speak(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "T") }

    private fun updateStatus(msg: String) {
        sendBroadcast(Intent("TONY_STATUS").setPackage(packageName).putExtra("msg", msg))
        val n = NotificationCompat.Builder(this, "TonyChannel").setContentTitle("Tony Pro Max").setContentText(msg).setSmallIcon(android.R.drawable.ic_btn_speak_now).build()
        getSystemService(NotificationManager::class.java).notify(1, n)
    }
    private fun initEngine() {
        Thread {
            try {
                val dir = File(filesDir, "vosk-model")
                if (!File(dir, "conf/mfcc.conf").exists()) {
                    dir.deleteRecursively()
                    updateStatus("⏳ ماڈل ڈاؤن لوڈ ہو رہا ہے (42 MB)...")
                    speak("پہلی بار آف لائن ماڈل ڈاؤن لوڈ کر رہا ہوں")
                    downloadModel(dir)
                }
                updateStatus("⚙️ ماڈل لوڈ ہو رہا ہے...")
                model = Model(dir.absolutePath)
                speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
                speechService?.startListening(this)
                updateStatus("🟢 آف لائن سن رہا ہے!\nکہیں: 'ٹونی' یا 'سنو'")
                speak("ٹونی مکمل طور پر تیار ہے")
            } catch (e: Exception) {
                updateStatus("❌ خرابی: ${e.message}")
            }
        }.start()
    }

    private fun downloadModel(dir: File) {
        dir.mkdirs()
        val zip = File(cacheDir, "m.zip")
        val conn = URL(getModelUrl()).openConnection() as HttpURLConnection
        val total = conn.contentLength
        var dl = 0
        var last = -1
        conn.inputStream.use { inp ->
            FileOutputStream(zip).use { out ->
                val buf = ByteArray(8192)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    dl += n
                    if (total > 0) {
                        val p = (dl * 100) / total
                        if (p != last && p % 5 == 0) { last = p; updateStatus("⏳ ڈاؤن لوڈ ہو رہا ہے: $p%") }
                    }
                }
            }
        }
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val parts = e.name.split("/")
                if (parts.size > 1) {
                    val f = File(dir, parts.drop(1).joinToString("/"))
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zis.copyTo(it) } }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
    }

    override fun onPartialResult(h: String?) {}
    override fun onFinalResult(h: String?) { onResult(h) }
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onResult(h: String?) {
        val text = try { JSONObject(h ?: "").optString("text", "").trim() } catch (_: Exception) { "" }
        if (text.isNotEmpty()) {
            updateStatus("🎤 سنا گیا: $text")
            handleCmd(text)
        }
    }

    private fun handleCmd(cmd: String) {
        if (cmd.contains("टोनी") || cmd.contains("टॉनी") || cmd.contains("tony") || cmd.contains("सुनो")) {
            isAwake = true
            updateStatus("🟢 جی، حکم کریں؟")
            speak("جی، حکم کریں؟")
            return
        }
        if (isAwake) {
            isAwake = false
            when {
                cmd.contains("टॉर्च") || cmd.contains("लाइट") -> torch(!cmd.contains("बंद"))
                cmd.contains("व्हाट्सएप") -> openApp("com.whatsapp", "واٹس ایپ کھول رہا ہوں")
                cmd.contains("यूट्यूब") -> openApp("com.google.android.youtube", "یوٹیوب کھول رہا ہوں")
                cmd.contains("टाइम") || cmd.contains("वक्त") -> speak("اس وقت ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} ہوئے ہیں")
                else -> speak("آپ نے کہا: $cmd")
            }
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull()?.let { cm.setTorchMode(it, on); speak(if (on) "ٹارچ آن کر دی ہے" else "ٹارچ بند کر دی ہے") }
        } catch (_: Exception) { speak("ٹارچ کنٹرول نہیں ہو سکی") }
    }

    private fun openApp(pkg: String, msg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); speak(msg) } else speak("یہ ایپ انسٹال نہیں ہے")
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("TonyChannel", "Tony", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, "TonyChannel").setContentTitle("Tony Pro Max").setContentText("تیار ہو رہا ہے...").setSmallIcon(android.R.drawable.ic_btn_speak_now).build())
        return START_STICKY
    }

    override fun onDestroy() { speechService?.stop(); speechService?.shutdown(); model?.close(); tts?.shutdown(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
