package com.tony.promax

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class TonyService : Service(), RecognitionListener, TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var liveStatus: String = "ٹونی بند ہے"
        private const val CHANNEL_ID = "tony_channel"
        private const val NOTIF_ID = 117
        private const val MODEL_NAME = "vosk-model-small-hi-0.22"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
        private const val ACTIVE_WINDOW_MS = 25000L
        private val WAKE_WORDS = listOf("टोनी", "टॉनी", "tony", "सुनो", "हेलो", "भाई", "दोस्त")
        private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
        private val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
        private val ALLOWED_ACTIONS = setOf(
            "NONE", "TORCH_ON", "TORCH_OFF", "OPEN_WHATSAPP", "OPEN_YOUTUBE", "OPEN_TIKTOK",
            "OPEN_FACEBOOK", "OPEN_CAMERA", "OPEN_SETTINGS", "OPEN_WIFI", "OPEN_BLUETOOTH", "TIME", "CALL"
        )
    }

    private data class GeminiResult(
        val action: String,
        val target: String,
        val reply: String,
        val raw: String,
        val error: String?
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val history = ArrayList<Pair<String, String>>()

    @Volatile private var speaking = false
    @Volatile private var busy = false
    @Volatile private var started = false
    @Volatile private var holdStatus = false
    @Volatile private var extendAfterSpeak = true
    @Volatile private var activeUntil = 0L
    @Volatile private var speakGen = 0

    // ---------- UI status (صرف اردو، دیوناگری خود بخود ہٹ جاتی ہے) ----------
    private fun setStatus(s: String) {
        val clean = DEVANAGARI.replace(s, "").trim()
        liveStatus = if (clean.isEmpty()) "..." else clean
    }

    private fun idleText(): String {
        return if (System.currentTimeMillis() < activeUntil)
            "ٹونی سن رہا ہے — اب اپنی بات بولیں"
        else
            "ٹونی سن رہا ہے — 'ٹونی' کہہ کر پکاریں"
    }

    private fun refreshIdle() {
        mainHandler.postDelayed({
            if (!speaking && !busy && !holdStatus && System.currentTimeMillis() >= activeUntil) {
                setStatus(idleText())
            }
        }, ACTIVE_WINDOW_MS + 300)
    }

    // ---------- Service lifecycle ----------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Tony", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        startFg()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setStatus("مائیکروفون کی اجازت نہیں ہے")
            } else {
                started = true
                initVosk()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { speechService?.stop() } catch (e: Exception) { }
        try { speechService?.shutdown() } catch (e: Exception) { }
        try { model?.close() } catch (e: Exception) { }
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        worker.shutdownNow()
        netExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        started = false
        setStatus("ٹونی بند ہے")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setContentTitle("ٹونی پرو میکس")
            .setContentText("ٹونی آپ کی آواز سن رہا ہے")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun startFg() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            try { startForeground(NOTIF_ID, n) } catch (e2: Exception) {
                setStatus("سروس شروع نہیں ہو سکی: ${e2.message}")
            }
        }
    }

    // ---------- TTS ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            setStatus("بولنے کا انجن شروع نہیں ہوا")
            return
        }
        val t = tts ?: return
        val r = t.setLanguage(Locale("ur", "PK"))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale("hi", "IN"))
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { onSpeakStart() }
            override fun onDone(utteranceId: String?) { onSpeakEnd() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onSpeakEnd() }
            override fun onError(utteranceId: String?, errorCode: Int) { onSpeakEnd() }
        })
        ttsReady = true
    }

    private fun speak(text: String, extend: Boolean = true, hold: Boolean = false) {
        val clean = DEVANAGARI.replace(text, "").trim()
        if (clean.isEmpty()) return
        holdStatus = hold
        extendAfterSpeak = extend
        if (!hold) setStatus("ٹونی: $clean")
        if (!ttsReady) return
        speakGen++
        speaking = true
        speechService?.setPause(true)
        val r = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "tony_" + System.currentTimeMillis())
        if (r != TextToSpeech.SUCCESS) onSpeakEnd()
    }

    private fun onSpeakStart() {
        speaking = true
        speechService?.setPause(true)
    }

    private fun onSpeakEnd() {
        val gen = speakGen
        mainHandler.postDelayed({
            if (gen != speakGen) return@postDelayed
            try {
                speechService?.reset()
                speechService?.setPause(false)
            } catch (e: Exception) { }
            speaking = false
            if (extendAfterSpeak) activeUntil = System.currentTimeMillis() + ACTIVE_WINDOW_MS
            if (!holdStatus) {
                setStatus(idleText())
                refreshIdle()
            }
        }, 500)
    }

    // ---------- Vosk ----------
    private fun initVosk() {
        worker.execute {
            try {
                val dir = File(filesDir, MODEL_NAME)
                if (!File(dir, "am").exists()) downloadModel(dir)
                setStatus("آواز کا ماڈل لوڈ ہو رہا ہے...")
                val m = Model(dir.absolutePath)
                model = m
                val rec = Recognizer(m, 16000.0f)
                val ss = SpeechService(rec, 16000.0f)
                speechService = ss
                ss.startListening(this)
                setStatus(idleText())
                mainHandler.post { speak("ٹونی تیار ہے", false, false) }
            } catch (t: Throwable) {
                started = false
                setStatus("ماڈل کی خرابی: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun downloadModel(targetDir: File) {
        val zipFile = File(cacheDir, "model.zip")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.connect()
            if (conn.responseCode != 200) throw Exception("ڈاؤن لوڈ HTTP ${conn.responseCode}")
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { out ->
                    val buf = ByteArray(16384)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                setStatus("ماڈل ڈاؤن لوڈ ہو رہا ہے: ${p}%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }
        setStatus("ماڈل کھولا جا رہا ہے...")
        unzip(zipFile, filesDir)
        zipFile.delete()
        if (!File(targetDir, "am").exists()) throw Exception("ماڈل کی فائلیں نامکمل ہیں")
    }

    private fun unzip(zip: File, destDir: File) {
        val destPath = destDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destPath + File.separator)) {
                    throw SecurityException("زپ فائل درست نہیں")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (speaking || busy || hypothesis == null) return
        val p = try { JSONObject(hypothesis).optString("partial", "") } catch (e: Exception) { "" }
        if (p.isNotBlank() && System.currentTimeMillis() < activeUntil) {
            setStatus("آپ کی بات سنی جا رہی ہے...")
        }
    }

    override fun onResult(hypothesis: String?) { handleText(hypothesis) }
    override fun onFinalResult(hypothesis: String?) { handleText(hypothesis) }
    override fun onTimeout() { }
    override fun onError(exception: Exception?) {
        setStatus("سننے میں خرابی: ${exception?.message ?: ""}")
    }

    // ---------- ویک ورڈ اور 25 سیکنڈ کی ونڈو ----------
    private fun handleText(json: String?) {
        if (json == null || speaking || busy) return
        val text = try { JSONObject(json).optString("text", "") } catch (e: Exception) { "" }.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        val lower = text.lowercase(Locale.ROOT)
        val wasActive = now < activeUntil

        var wakeIdx = -1
        var wakeLen = 0
        for (w in WAKE_WORDS) {
            val i = lower.indexOf(w)
            if (i >= 0 && (wakeIdx < 0 || i < wakeIdx)) {
                wakeIdx = i
                wakeLen = w.length
            }
        }
        if (wakeIdx < 0 && !wasActive) return

        val cmd = if (wakeIdx >= 0 && (!wasActive || wakeIdx == 0))
            lower.substring(wakeIdx + wakeLen).trim()
        else text

        activeUntil = now + ACTIVE_WINDOW_MS
        if (cmd.length < 3) {
            speak("جی، فرمائیے")
        } else {
            processCommand(cmd)
        }
    }

    // ---------- Gemini ----------
    private fun processCommand(cmd: String) {
        busy = true
        holdStatus = false
        setStatus("ٹونی سوچ رہا ہے...")
        netExecutor.execute {
            val res = try {
                val key = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE)
                    .getString("api_key", "")?.trim().orEmpty()
                if (key.isEmpty()) {
                    GeminiResult("NONE", "", "", "", "جیمنی API کلید محفوظ نہیں ہے۔ ایپ میں کلید درج کر کے محفوظ کریں")
                } else askGemini(key, cmd)
            } catch (t: Throwable) {
                GeminiResult("NONE", "", "", "", "اندرونی خرابی: ${t.javaClass.simpleName} ${t.message ?: ""}")
            }
            mainHandler.post {
                if (res.error != null) {
                    setStatus(res.error)
                    speak("جیمنی سے رابطہ نہیں ہو سکا، اسکرین پر خرابی دیکھیں", false, true)
                    busy = false
                } else {
                    val finalReply = try {
                        performAction(res.action, res.target, res.reply)
                    } catch (t: Throwable) {
                        "یہ کام نہیں ہو سکا"
                    }
                    speak(finalReply)
                    busy = false
                }
            }
        }
    }

    private fun askGemini(key: String, userText: String): GeminiResult {
        val body = buildBody(userText)
        var firstError: String? = null
        var lastError = "نامعلوم خرابی"
        for (m in GEMINI_MODELS) {
            for (useHeader in listOf(true, false)) {
                val r = callOnce(m, key, useHeader, body)
                if (r.error == null) {
                    history.add(Pair(userText, r.raw))
                    while (history.size > 6) history.removeAt(0)
                    return r
                }
                lastError = r.error
                if (firstError == null) firstError = r.error
            }
        }
        val msg = if (firstError == null || firstError == lastError) lastError
        else firstError + "\n" + lastError
        return GeminiResult("NONE", "", "", "", msg)
    }

    private fun buildBody(userText: String): String {
        val sys = "You are Tony Pro Max, a friendly Urdu voice assistant created by Doctor Raqeeb Ali " +
            "(ڈاکٹر رقیب علی 117 سکسار والا). The user's speech was transcribed by an offline Hindi " +
            "speech recognizer, so it may be Hindi or Urdu words written in Devanagari with small " +
            "mistakes; understand the meaning. Reply ONLY with one JSON object of this exact shape: " +
            "{\"action\": \"...\", \"target\": \"...\", \"reply\": \"...\"}. " +
            "action must be exactly one of: NONE, TORCH_ON, TORCH_OFF, OPEN_WHATSAPP, OPEN_YOUTUBE, " +
            "OPEN_TIKTOK, OPEN_FACEBOOK, OPEN_CAMERA, OPEN_SETTINGS, OPEN_WIFI, OPEN_BLUETOOTH, TIME, CALL. " +
            "Use NONE for normal questions and chat (answer them fully and helpfully in reply). " +
            "Use TIME when the user asks the current time. " +
            "Use CALL when the user wants to phone someone and set target to the contact name in English letters. " +
            "For all other actions target is an empty string. " +
            "reply must be natural, polite, short (1 to 3 sentences) Urdu written ONLY in Urdu/Arabic script. " +
            "Never use Devanagari or Hindi script. No markdown, no emojis."
        val root = JSONObject()
        root.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        val contents = JSONArray()
        for ((u, mdl) in history) {
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("text", mdl))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText))))
        root.put("contents", contents)
        root.put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("temperature", 0.7)
            .put("maxOutputTokens", 2048))
        return root.toString()
    }

    private fun callOnce(model: String, key: String, useHeader: Boolean, body: String): GeminiResult {
        val tag = model + (if (useHeader) " [header]" else " [?key]")
        var conn: HttpURLConnection? = null
        try {
            val base = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent"
            val url = if (useHeader) base else base + "?key=" + URLEncoder.encode(key, "UTF-8")
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (useHeader) conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                var msg = try {
                    JSONObject(resp).optJSONObject("error")?.optString("message", "") ?: ""
                } catch (e: Exception) { "" }
                if (msg.isBlank()) msg = resp
                return fail("جیمنی ${tag} — HTTP ${code}: ${msg.take(220)}")
            }
            return parseGemini(resp, tag)
        } catch (e: Exception) {
            return fail("جیمنی ${tag} — نیٹ ورک خرابی: ${e.javaClass.simpleName} ${e.message ?: ""}".take(260))
        } finally {
            conn?.disconnect()
        }
    }

    private fun fail(msg: String) = GeminiResult("NONE", "", "", "", msg)

    private fun parseGemini(resp: String, tag: String): GeminiResult {
        try {
            val root = JSONObject(resp)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val br = root.optJSONObject("promptFeedback")?.optString("blockReason", "") ?: ""
                return fail("جیمنی ${tag} — خالی جواب ملا ${br}")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            val raw = sb.toString().trim()
            if (raw.isEmpty()) return fail("جیمنی ${tag} — جواب میں متن نہیں تھا")
            val clean = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            var action = "NONE"
            var target = ""
            var reply = ""
            val s = clean.indexOf('{')
            val e = clean.lastIndexOf('}')
            if (s >= 0 && e > s) {
                try {
                    val j = JSONObject(clean.substring(s, e + 1))
                    action = j.optString("action", "NONE").trim().uppercase(Locale.ROOT)
                    target = j.optString("target", "").trim()
                    reply = j.optString("reply", "").trim()
                } catch (ex: Exception) {
                    reply = clean
                }
            } else {
                reply = clean
            }
            if (action !in ALLOWED_ACTIONS) action = "NONE"
            target = DEVANAGARI.replace(target, "").trim()
            reply = DEVANAGARI.replace(reply, "").trim()
            if (reply.isEmpty()) reply = "جی ٹھیک ہے"
            return GeminiResult(action, target, reply, clean, null)
        } catch (ex: Exception) {
            return fail("جیمنی ${tag} — جواب سمجھ نہیں آیا: ${ex.message ?: ""}".take(260))
        }
    }

    // ---------- ایکشنز ----------
    private fun performAction(action: String, target: String, reply: String): String {
        return when (action) {
            "TORCH_ON" -> if (setTorch(true)) reply else "ٹارچ آن نہیں ہو سکی"
            "TORCH_OFF" -> if (setTorch(false)) reply else "ٹارچ بند نہیں ہو سکی"
            "OPEN_WHATSAPP" -> openApp(listOf("com.whatsapp", "com.whatsapp.w4b"), "https://wa.me/", reply, "واٹس ایپ نہیں کھل سکا")
            "OPEN_YOUTUBE" -> openApp(listOf("com.google.android.youtube"), "https://www.youtube.com", reply, "یوٹیوب نہیں کھل سکا")
            "OPEN_TIKTOK" -> openApp(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com", reply, "ٹک ٹاک نہیں کھل سکا")
            "OPEN_FACEBOOK" -> openApp(listOf("com.facebook.katana"), "https://www.facebook.com", reply, "فیس بک نہیں کھل سکا")
            "OPEN_CAMERA" -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE), reply, "کیمرہ نہیں کھل سکا")
            "OPEN_SETTINGS" -> launch(Intent(Settings.ACTION_SETTINGS), reply, "سیٹنگز نہیں کھل سکیں")
            "OPEN_WIFI" -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), reply, "وائی فائی سیٹنگز نہیں کھل سکیں")
            "OPEN_BLUETOOTH" -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), reply, "بلوٹوتھ سیٹنگز نہیں کھل سکیں")
            "TIME" -> timeReply()
            "CALL" -> makeCall(target, reply)
            else -> reply
        }
    }

    private fun launch(intent: Intent, okReply: String, failReply: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            okReply
        } catch (e: Exception) {
            failReply
        }
    }

    private fun openApp(pkgs: List<String>, fallbackUrl: String, okReply: String, failReply: String): String {
        for (p in pkgs) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) return launch(i, okReply, failReply)
        }
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)), okReply, failReply)
    }

    private fun setTorch(on: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun timeReply(): String {
        val c = Calendar.getInstance()
        val h24 = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val part = when {
            h24 < 4 -> "رات"
            h24 < 12 -> "صبح"
            h24 < 16 -> "دوپہر"
            h24 < 19 -> "شام"
            else -> "رات"
        }
        return "اس وقت ${part} کے ${h12} بج کر ${m} منٹ ہوئے ہیں"
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name + "%"),
            null
        )
        cursor?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun makeCall(target: String, reply: String): String {
        if (target.isBlank()) return "کس کو کال کرنی ہے؟ نام بتائیں"
        val isNumber = target.matches(Regex("[+0-9 ()-]{5,}"))
        if (!isNumber && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "رابطوں کی اجازت نہیں ہے، ایپ کو اجازت دیں"
        }
        val number = if (isNumber) target.filter { it.isDigit() || it == '+' } else findNumber(target)
        if (number.isNullOrEmpty()) return "یہ نام رابطوں میں نہیں ملا"
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val act = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return launch(Intent(act, Uri.parse("tel:" + Uri.encode(number))), reply, "کال نہیں ہو سکی")
    }
}
