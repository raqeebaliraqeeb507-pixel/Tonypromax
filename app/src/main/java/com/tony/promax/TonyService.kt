package com.tony.promax

import android.app.*
import android.content.*
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.*
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.*
import org.vosk.android.*
import java.io.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipInputStream

class TonyService : Service(), TextToSpeech.OnInitListener, RecognitionListener {
    companion object {
        @Volatile
        var liveStatus: String = "Tony Pro Max تیار ہے\nکہیں: ٹونی یا سنو"
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var tts: TextToSpeech? = null
    private var awakeUntil: Long = 0L
    private var isStarted = false

    @Volatile
    private var isSpeakingNow = false
    @Volatile
    private var lastSpeakEndTime = 0L

    private fun decodeB64(s: String) = String(Base64.decode(s, 0))

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("TonyChannel", "Tony", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(1, buildNotif("تیار ہو رہا ہے..."))
        tts = TextToSpeech(this, this)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeakingNow = true }
            override fun onDone(utteranceId: String?) {
                isSpeakingNow = false
                lastSpeakEndTime = System.currentTimeMillis()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isSpeakingNow = false
                lastSpeakEndTime = System.currentTimeMillis()
            }
        })
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (!isStarted) { isStarted = true; initEngine() }
        return START_STICKY
    }

    private fun buildNotif(msg: String) = NotificationCompat.Builder(this, "TonyChannel")
        .setContentTitle("Tony Pro Max")
        .setContentText(msg)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .build()

    private fun updateStatus(msg: String) {
        liveStatus = msg
        getSystemService(NotificationManager::class.java).notify(1, buildNotif(msg))
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ur", "PK"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("hi", "IN"))
            }
        }
    }

    private fun speak(t: String) {
        isSpeakingNow = true
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "T_${System.currentTimeMillis()}")
    }

    private fun initEngine() {
        Thread {
            try {
                val dir = File(filesDir, "vosk-model")
                if (!File(dir, "conf/mfcc.conf").exists()) {
                    dir.deleteRecursively()
                    updateStatus("⏳ پہلی بار آواز کا ماڈل ڈاؤن لوڈ ہو رہا ہے (42 MB)...")
                    downloadModel(dir)
                }
                model = Model(dir.absolutePath)
                speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
                speechService?.startListening(this)
                updateStatus("🟢 ٹونی جاگ رہا ہے!\nکہیں: ٹونی، سنو، ہیلو، یا بھائی")
                speak("ٹونی مکمل طور پر تیار ہے، حکم کریں ڈاکٹر صاحب")
            } catch (e: Exception) {
                isStarted = false
                updateStatus("❌ خرابی: ${e.message}")
            }
        }.start()
    }

    private fun downloadModel(dir: File) {
        dir.mkdirs()
        val zip = File(cacheDir, "m.zip")
        val url = decodeB64("aHR0cHM6Ly9hbHBoYWNlcGhlaS5jb20vdm9zay9tb2RlbHMvdm9zay1tb2RlbC1zbWFsbC1oaS0wLjIyLnppcA==")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.inputStream.use { inp -> FileOutputStream(zip).use { inp.copyTo(it) } }
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val parts = e.name.split("/")
                if (parts.size > 1) {
                    val f = File(dir, parts.drop(1).joinToString("/"))
                    if (e.isDirectory) f.mkdirs() else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zis.copyTo(it) }
                    }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
    }

    private fun shouldIgnoreAudio(): Boolean {
        if (isSpeakingNow || tts?.isSpeaking == true) return true
        if (System.currentTimeMillis() - lastSpeakEndTime < 800L) return true
        return false
    }

    override fun onPartialResult(h: String?) {
        if (shouldIgnoreAudio()) return
        val p = try { JSONObject(h ?: "").optString("partial", "").trim() } catch (_: Exception) { "" }
        if (p.isNotEmpty()) {
            liveStatus = "🎙️ آپ کی آواز سن رہا ہوں..."
        }
    }

    override fun onFinalResult(h: String?) { onResult(h) }
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onResult(h: String?) {
        if (shouldIgnoreAudio()) return
        val text = try { JSONObject(h ?: "").optString("text", "").trim() } catch (_: Exception) { "" }
        if (text.isNotEmpty()) {
            processInput(text)
        }
    }

    private fun processInput(rawCmd: String) {
        var cmd = rawCmd.lowercase(Locale.getDefault())
        val wakes = listOf(
            "टोनी", "टॉनी", "तोनी", "तूनी", "टूनी", "tony", "toni",
            "सुनो", "सुनों", "हेलो", "हैलो", "भाई", "दोस्त"
        )
        val now = System.currentTimeMillis()
        val hasWakeWord = wakes.any { cmd.contains(it) }

        if (hasWakeWord) {
            awakeUntil = now + 25000L
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) {
                updateStatus("🟢 جی ڈاکٹر صاحب، حکم کریں؟")
                speak("جی ڈاکٹر صاحب، حکم کریں؟")
                return
            }
        } else if (now > awakeUntil) {
            return
        }

        awakeUntil = now + 25000L
        updateStatus("🧠 بات سمجھ رہا ہوں...")

        val savedKey = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""
        val apiKey = if (savedKey.isNotEmpty()) savedKey else decodeB64("QVEuQWI4Uk42SjVhUUg3bjZOTUFhbmRkdmdWbzBWUW8yTzFya1J0eXdmS3dqRnVQNWZsaVE=")
        if (apiKey.isNotEmpty()) {
            Thread { askGeminiBrain(cmd, apiKey) }.start()
        } else {
            updateStatus("⚠️ API Key خالی ہے، آف لائن موڈ چل رہا ہے")
            runOfflineBrain(cmd)
        }
    }

    private fun askGeminiBrain(userText: String, apiKey: String) {
        val models = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
        val host = decodeB64("aHR0cHM6Ly9nZW1pbmlhcGkuZ29vZ2xlYXBpcy5jb20vdjFiZXRhL21vZGVscy8=")
        var lastErr = ""

        for (m in models) {
            try {
                updateStatus("🤖 ٹونی سوچ رہا ہے ($m)...")
                val conn = URL("$host$m:generateContent").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", apiKey.trim())
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true

                val prompt = "You are Tony Pro Max, an intelligent Urdu voice assistant created by Doctor Raqeeb Ali 117 Siksar Wala (ڈاکٹر رقیب علی 117 سکسار والا). " +
                    "The user spoke in Urdu, which was transcribed phonetically into Hindi/Devanagari text: '$userText'. " +
                    "Understand the Urdu meaning accurately, answer their question or confirm the phone command. " +
                    "CRITICAL RULE: Write the 'reply' ONLY in pure, natural Urdu script (نستعلیق اردو). NEVER use Hindi/Devanagari characters in 'reply'. " +
                    "If action is CALL, convert the person's name into English/Roman letters (e.g. 'Ali', 'Ahmed') in 'target'. " +
                    "Reply ONLY in valid JSON without markdown: " +
                    "{\"action\": \"NONE|TORCH_ON|TORCH_OFF|OPEN_WHATSAPP|OPEN_YOUTUBE|OPEN_TIKTOK|OPEN_FACEBOOK|OPEN_CAMERA|OPEN_SETTINGS|OPEN_WIFI|OPEN_BLUETOOTH|TIME|CALL\", " +
                    "\"target\": \"contact name in English or empty\", \"reply\": \"short natural Urdu reply\"}"

                val body = JSONObject().put(
                    "contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val statusCode = conn.responseCode
                if (statusCode != 200) {
                    val errDetail = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    lastErr = "HTTP $statusCode: ${errDetail.take(60)}"
                    continue
                }

                val resp = conn.inputStream.bufferedReader().readText()
                val rawAns = JSONObject(resp)
                    .getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text")

                val cleaned = rawAns.replace("```json", "").replace("```", "").trim()
                val obj = JSONObject(cleaned)
                val reply = obj.optString("reply", "جی بہتر ڈاکٹر صاحب")
                val action = obj.optString("action", "NONE")
                val target = obj.optString("target", "")

                updateStatus("💬 ٹونی: $reply")
                speak(reply)
                executeAction(action, target)
                return
            } catch (e: Exception) {
                lastErr = e.message ?: "Error"
            }
        }
        updateStatus("⚠️ AI مسئلہ ($lastErr)"); speak("گوگل سرور سے رابطہ نہیں ہو سکا")
        runOfflineBrain(userText)
    }

    private fun runOfflineBrain(cmd: String) {
        when {
            cmd.contains("किसने बनाया") || cmd.contains("खालिक") || cmd.contains("बनाने वाले") || cmd.contains("रकीब") -> {
                val m = "مجھے ڈاکٹر رقیب علی 117 سکسار والا نے بنایا ہے"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("टॉर्च") || cmd.contains("लाइट") || cmd.contains("बत्ती") || cmd.contains("torch") -> {
                val on = !cmd.contains("बंद") && !cmd.contains("off")
                executeAction(if (on) "TORCH_ON" else "TORCH_OFF", "")
                val m = if (on) "ٹارچ آن کر دی ہے" else "ٹارچ بند کر دی ہے"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("व्हाट्सएप") || cmd.contains("वाट्सएप") || cmd.contains("whatsapp") -> {
                executeAction("OPEN_WHATSAPP", "")
                val m = "واٹس ایپ کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("यूट्यूब") || cmd.contains("youtube") -> {
                executeAction("OPEN_YOUTUBE", "")
                val m = "یوٹیوب کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("टिकटॉक") || cmd.contains("टिक टॉक") || cmd.contains("tiktok") -> {
                executeAction("OPEN_TIKTOK", "")
                val m = "ٹک ٹاک کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("फेसबुक") || cmd.contains("facebook") -> {
                executeAction("OPEN_FACEBOOK", "")
                val m = "فیس بک کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("कैमरा") || cmd.contains("camera") -> {
                executeAction("OPEN_CAMERA", "")
                val m = "کیمرہ کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("वाईफाई") || cmd.contains("वाइफाइ") || cmd.contains("wifi") -> {
                executeAction("OPEN_WIFI", "")
                val m = "وائی فائی سیٹنگز کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("ब्लूटूथ") || cmd.contains("bluetooth") -> {
                executeAction("OPEN_BLUETOOTH", "")
                val m = "بلوٹوتھ سیٹنگز کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("सेटिंग") || cmd.contains("settings") -> {
                executeAction("OPEN_SETTINGS", "")
                val m = "سیٹنگز کھول رہا ہوں"
                updateStatus("💬 $m"); speak(m)
            }
            cmd.contains("टाइम") || cmd.contains("वक्त") || cmd.contains("समय") || cmd.contains("time") -> {
                executeAction("TIME", "")
            }
            cmd.contains("कॉल") || cmd.contains("फोन") || cmd.contains("call") -> {
                val target = cmd.replace("कॉल", "").replace("करो", "").replace("को", "").replace("फोन", "").replace("call", "").trim()
                executeAction("CALL", target)
            }
            else -> {
                val m = "آف لائن موڈ میں یہ کمانڈ موجود نہیں ہے، براہِ کرم انٹرنیٹ یا API Key چیک کریں"
                updateStatus("💬 $m"); speak(m)
            }
        }
    }

    private fun executeAction(action: String, target: String) {
        when (action) {
            "TORCH_ON" -> torch(true)
            "TORCH_OFF" -> torch(false)
            "OPEN_WHATSAPP" -> openApp("com.whatsapp")
            "OPEN_YOUTUBE" -> openApp("com.google.android.youtube")
            "OPEN_TIKTOK" -> openApp("com.zhiliaoapp.musically")
            "OPEN_FACEBOOK" -> openApp("com.facebook.katana")
            "OPEN_CAMERA" -> startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "OPEN_SETTINGS" -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "OPEN_WIFI" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "OPEN_BLUETOOTH" -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "TIME" -> {
                val t = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
                val m = "اس وقت $t ہو رہے ہیں"
                updateStatus("💬 $m")
                speak(m)
            }
            "CALL" -> callContact(target)
        }
    }

    private fun torch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull()?.let { cm.setTorchMode(it, on) }
        } catch (_: Exception) {}
    }

    private fun openApp(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun callContact(name: String) {
        if (name.isEmpty()) return
        try {
            val c = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            if (c != null && c.moveToFirst()) {
                val num = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                c.close()
                speak("$name کو کال ملا رہا ہوں")
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                speak("$name کا نمبر فون بک میں نہیں ملا")
            }
        } catch (_: Exception) {
            speak("کال ملانے میں مسئلہ آیا ہے، پرمیشن چیک کریں")
        }
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
