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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

class TonyService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var liveStatus: String = "ٹونی بند ہے"
        private const val CHANNEL_ID = "tony_channel"
        private const val NOTIF_ID = 117
        private const val SLEEP_MS = 12000L
        private val GEMINI_MODELS = listOf(
            "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash"
        )
        private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
        private val ALLOWED_ACTIONS = setOf(
            "NONE", "TORCH_ON", "TORCH_OFF", "OPEN_WHATSAPP", "OPEN_YOUTUBE", "OPEN_TIKTOK",
            "OPEN_FACEBOOK", "OPEN_CAMERA", "OPEN_SETTINGS", "OPEN_WIFI", "OPEN_BLUETOOTH", "TIME", "CALL"
        )
        private val WAKE_REGEXES = listOf(
            Regex("(بات\\s*)?(سنو|سنتے\\s*ہو)\\s*(ٹونی|ٹوني|ٹانی|ٹونے|ٹنی|tony|toni|tonny)"),
            Regex("(baat\\s*)?(suno|sunno|sunte\\s*ho)\\s*(tony|toni|tonny)")
        )
        private const val SYSTEM_PROMPT =
            "You are Tony, a personal AI assistant. Important facts: Dr. Raqeeb Ali (ڈاکٹر رقیب علی) " +
            "lives in 117/6-R, District Bahawalnagar, Pakistan (117 سکس آر، ضلع بہاولنگر). " +
            "Always answer concisely in 1-2 sentences in Urdu. Never guess or make up personal facts; " +
            "if you don't know something, simply say so. " +
            "Output format: the very first line of every answer must be a tag in square brackets. " +
            "Use [NONE] for normal chat and questions. Use one of [TORCH_ON] [TORCH_OFF] [OPEN_WHATSAPP] " +
            "[OPEN_YOUTUBE] [OPEN_TIKTOK] [OPEN_FACEBOOK] [OPEN_CAMERA] [OPEN_SETTINGS] [OPEN_WIFI] " +
            "[OPEN_BLUETOOTH] [TIME] for device commands. Use [CALL:Name] to phone someone, where Name " +
            "is the contact name in English letters. After the tag, on a new line, write the Urdu reply " +
            "using only Urdu/Arabic script (never Devanagari or Hindi script), plain text, no markdown, " +
            "no emojis. The user's speech comes from a speech recognizer and may contain small mistakes; " +
            "understand the intended meaning."
    }

    private class StreamError(val code: Int, val text: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val netExecutor = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var greeted = false
    private val history = ArrayList<Pair<String, String>>()
    @Volatile private var goodModel: String? = null

    private var isAwake = false
    private var processing = false
    private var listening = false
    private var destroyed = false
    private var holdStatus = false
    private var sleepArmed = false
    private var turnId = 0
    private var doneTurn = -1
    private var pending = 0
    private var replyFinished = false
    private var firstChunk = true
    private var uttCounter = 0
    private var curReply: Reply? = null

    // ---------- اردو اسٹیٹس ----------
    private fun setStatus(s: String) {
        val clean = DEVANAGARI.replace(s, "").trim()
        liveStatus = if (clean.isEmpty()) "..." else clean
    }

    private fun stateText(): String {
        return if (isAwake) "ٹونی جاگ رہا ہے — اپنی بات بولیں"
        else "ٹونی سٹینڈ بائی — 'بات سنو ٹونی' کہیں"
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
        if (recognizer == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setStatus("مائیکروفون کی اجازت نہیں ہے")
            } else {
                initRecognizer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        netExecutor.shutdownNow()
        setStatus("ٹونی بند ہے")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        return b.setContentTitle("ٹونی پرو میکس")
            .setContentText("ٹونی آپ کی آواز کا منتظر ہے")
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
            override fun onStart(utteranceId: String?) {
                mainHandler.post { if (processing) stopRecognizer() }
            }
            override fun onDone(utteranceId: String?) {
                mainHandler.post { utteranceEnded() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { utteranceEnded() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { utteranceEnded() }
            }
        })
        ttsReady = true
        if (!greeted && recognizer != null && !processing) {
            greeted = true
            speakSimple("ٹونی تیار ہے")
        }
    }

    private fun speakChunk(text: String): Boolean {
        val t = tts
        if (!ttsReady || t == null) return false
        val mode = if (firstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val r = t.speak(text, mode, null, "tony_" + (++uttCounter))
        if (r != TextToSpeech.SUCCESS) return false
        firstChunk = false
        pending++
        return true
    }

    private fun utteranceEnded() {
        pending--
        if (pending <= 0) {
            pending = 0
            if (replyFinished) allSpoken()
        }
    }

    // ---------- SpeechRecognizer (ہمیشہ آن) ----------
    private val recIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
    }

    private val startRunnable = Runnable {
        if (destroyed || processing || listening) return@Runnable
        val r = recognizer ?: return@Runnable
        try {
            listening = true
            r.startListening(recIntent)
        } catch (e: Exception) {
            listening = false
            recreateRecognizer()
            restartListening(1000)
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("گوگل کی آواز پہچاننے والی سروس موجود نہیں ہے")
            return
        }
        recreateRecognizer()
        setStatus(stateText())
        restartListening(0)
    }

    private fun recreateRecognizer() {
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        listening = false
    }

    private fun restartListening(delay: Long) {
        mainHandler.removeCallbacks(startRunnable)
        mainHandler.postDelayed(startRunnable, delay)
    }

    private fun stopRecognizer() {
        listening = false
        mainHandler.removeCallbacks(startRunnable)
        try { recognizer?.cancel() } catch (e: Exception) { }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!processing && !holdStatus) setStatus(stateText())
        }
        override fun onBeginningOfSpeech() {
            if (isAwake && !processing) cancelSleepTimer()
        }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { }
        override fun onPartialResults(partialResults: Bundle?) {
            if (processing || !isAwake) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (p.isNotBlank()) {
                cancelSleepTimer()
                setStatus("آپ کی بات سنی جا رہی ہے...")
            }
        }
        override fun onResults(results: Bundle?) {
            listening = false
            if (processing) return
            val list: List<String> =
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            handleSpeech(list)
            if (!processing) restartListening(100)
        }
        override fun onError(error: Int) {
            listening = false
            if (processing) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && isAwake) armSleepTimer()
                    restartListening(100)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recreateRecognizer()
                    restartListening(800)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    setStatus("مائیکروفون کی اجازت نہیں ہے")
                }
                else -> {
                    setStatus("سننے میں خرابی (کوڈ ${error}) — دوبارہ کوشش ہو رہی ہے")
                    restartListening(1500)
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) { }
    }

    // ---------- 12 سیکنڈ آٹو سلیپ ----------
    private val sleepRunnable = Runnable {
        sleepArmed = false
        if (!processing && isAwake) {
            isAwake = false
            if (!holdStatus) setStatus(stateText())
        }
    }

    private fun armSleepTimer() {
        if (sleepArmed) return
        sleepArmed = true
        mainHandler.postDelayed(sleepRunnable, SLEEP_MS)
    }

    private fun cancelSleepTimer() {
        sleepArmed = false
        mainHandler.removeCallbacks(sleepRunnable)
    }

    // ---------- ویک فریز ----------
    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) {
            when {
                ch == 'ي' || ch == 'ى' -> sb.append('ی')
                ch == 'ك' -> sb.append('ک')
                ch == 'ة' || ch == 'ۃ' -> sb.append('ہ')
                ch in '\u064B'..'\u065F' -> { }
                ch == '\u200c' || ch == '\u200d' || ch == '\u200e' || ch == '\u200f' -> { }
                ch in ",.،۔!?؟\"'" -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun findWake(t: String): MatchResult? {
        for (r in WAKE_REGEXES) {
            val m = r.find(t)
            if (m != null) return m
        }
        return null
    }

    private fun handleSpeech(list: List<String>) {
        val texts = list.map { normalize(it) }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            if (isAwake) armSleepTimer()
            return
        }
        if (!isAwake) {
            for (t in texts) {
                val m = findWake(t) ?: continue
                isAwake = true
                onWake(t, m)
                return
            }
            return
        }
        val t = texts[0]
        val m = findWake(t)
        if (m != null) {
            onWake(t, m)
            return
        }
        if (t.length < 2) {
            armSleepTimer()
            return
        }
        ask(t)
    }

    private fun onWake(t: String, m: MatchResult) {
        val rest = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1)).trim()
        if (rest.length >= 3) ask(rest) else speakSimple("جی ڈاکٹر صاحب، حکم کریں")
    }

    // ---------- باری کا کنٹرول ----------
    private fun beginTurn(): Int {
        cancelSleepTimer()
        processing = true
        holdStatus = false
        turnId++
        firstChunk = true
        pending = 0
        replyFinished = false
        curReply = null
        stopRecognizer()
        return turnId
    }

    private fun speakSimple(text: String) {
        beginTurn()
        setStatus("ٹونی: $text")
        speakChunk(text)
        replyFinished = true
        if (pending == 0) allSpoken()
    }

    private fun failTurn(msg: String) {
        holdStatus = true
        setStatus(msg)
        speakChunk("جیمنی سے رابطہ نہیں ہو سکا، اسکرین پر خرابی دیکھیں")
        replyFinished = true
        if (pending == 0) allSpoken()
    }

    private fun allSpoken() {
        if (doneTurn == turnId) return
        doneTurn = turnId
        val t = turnId
        mainHandler.postDelayed({
            if (t != turnId || destroyed) return@postDelayed
            processing = false
            if (isAwake) armSleepTimer()
            if (!holdStatus) setStatus(stateText())
            restartListening(0)
        }, 500)
    }

    private fun ask(q: String) {
        val turn = beginTurn()
        setStatus("ٹونی سوچ رہا ہے...")
        val key = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE)
            .getString("api_key", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            failTurn("جیمنی API کلید محفوظ نہیں ہے۔ ایپ میں کلید درج کر کے محفوظ کریں")
            return
        }
        curReply = Reply(q)
        val snap = history.takeLast(2).toList()
        netExecutor.execute { runGemini(key, q, snap, turn) }
    }

    // ---------- جواب: پہلا جملہ مکمل ہوتے ہی بولنا ----------
    private inner class Reply(val userText: String) {
        private val buf = StringBuilder()
        private val full = StringBuilder()
        private var headerDone = false
        private var finished = false
        private var spoke = false
        private var forced: String? = null
        private var action = "NONE"
        private var target = ""

        fun onText(t: String) {
            if (finished) return
            buf.append(t)
            if (!headerDone && !parseHeader()) return
            flush(false)
        }

        private fun parseHeader(): Boolean {
            val s = buf.toString().trimStart()
            if (s.isEmpty()) return false
            if (s[0] == '[') {
                val e = s.indexOf(']')
                if (e < 0) return false
                val parts = s.substring(1, e).split(":", limit = 2)
                var a = parts[0].trim().uppercase(Locale.ROOT)
                if (a !in ALLOWED_ACTIONS) a = "NONE"
                action = a
                target = if (parts.size > 1) DEVANAGARI.replace(parts[1], "").trim() else ""
                buf.setLength(0)
                buf.append(s.substring(e + 1))
            } else {
                buf.setLength(0)
                buf.append(s)
            }
            headerDone = true
            forced = performAction(action, target)
            return true
        }

        private fun sentenceEnd(): Int {
            for (i in 0 until buf.length) {
                val c = buf[i]
                if (c == '۔' || c == '؟' || c == '?' || c == '!' || c == '\n') return i
                if (c == '.') {
                    if (i > 0 && buf[i - 1].isDigit()) {
                        if (i + 1 >= buf.length) return -1
                        if (buf[i + 1].isDigit()) continue
                    }
                    return i
                }
            }
            return -1
        }

        private fun emit(piece: String) {
            val clean = DEVANAGARI.replace(piece, "").trim()
            if (clean.none { it.isLetterOrDigit() }) return
            full.append(clean).append(' ')
            setStatus("ٹونی: " + full.toString().trim())
            if (speakChunk(clean)) spoke = true
        }

        private fun flush(last: Boolean) {
            while (forced == null) {
                val i = sentenceEnd()
                if (i < 0) break
                val piece = buf.substring(0, i + 1)
                buf.delete(0, i + 1)
                emit(piece)
            }
            if (last && forced == null && buf.isNotEmpty()) {
                emit(buf.toString())
                buf.setLength(0)
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            if (!headerDone) {
                headerDone = true
                if (buf.toString().trimStart().startsWith("[")) buf.setLength(0)
            }
            val f = forced
            if (f != null) {
                full.setLength(0)
                full.append(f)
                setStatus("ٹونی: $f")
                if (speakChunk(f)) spoke = true
            } else {
                flush(true)
            }
            if (!spoke && f == null) {
                full.append("جی ٹھیک ہے")
                setStatus("ٹونی: جی ٹھیک ہے")
                if (speakChunk("جی ٹھیک ہے")) spoke = true
            }
            val said = full.toString().trim()
            if (said.isNotEmpty()) {
                val tag = "[" + action + (if (target.isNotEmpty()) ":" + target else "") + "]"
                history.add(Pair(userText, tag + "\n" + said))
                while (history.size > 6) history.removeAt(0)
            }
            replyFinished = true
            if (pending == 0) allSpoken()
        }
    }

    // ---------- Gemini (SSE سٹریمنگ) ----------
    private fun runGemini(key: String, userText: String, hist: List<Pair<String, String>>, turn: Int) {
        val errors = ArrayList<String>()
        val streamed = BooleanArray(1)
        val order = LinkedHashSet<String>()
        goodModel?.let { order.add(it) }
        order.addAll(GEMINI_MODELS)
        var netFails = 0
        outer@ for (m in order) {
            var useHeader = true
            var withThinking = true
            var attempts = 0
            while (attempts < 4) {
                attempts++
                val err = streamOnce(m, key, useHeader, withThinking, userText, hist, turn, streamed)
                if (err == null) {
                    goodModel = m
                    mainHandler.post { if (turn == turnId) curReply?.finish() }
                    return
                }
                if (streamed[0]) {
                    mainHandler.post { if (turn == turnId) curReply?.finish() }
                    return
                }
                val low = err.text.lowercase(Locale.ROOT)
                if (err.code == 400 && withThinking && low.contains("think")) {
                    withThinking = false
                    continue
                }
                if ((err.code == 401 || err.code == 403) && useHeader) {
                    useHeader = false
                    continue
                }
                val label = if (err.code > 0) "HTTP ${err.code}: " else ""
                errors.add("${m} — ${label}${err.text}")
                if (err.code == 0) netFails++
                if (netFails >= 2) break@outer
                break
            }
        }
        val msg = when {
            errors.isEmpty() -> "نامعلوم خرابی"
            errors.size == 1 -> errors[0]
            else -> errors.first() + "\n" + errors.last()
        }
        mainHandler.post { if (turn == turnId) failTurn("جیمنی خرابی:\n" + msg) }
    }

    private fun streamOnce(
        model: String, key: String, useHeader: Boolean, withThinking: Boolean,
        userText: String, hist: List<Pair<String, String>>, turn: Int, streamed: BooleanArray
    ): StreamError? {
        var conn: HttpURLConnection? = null
        try {
            var url = "https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse"
            if (!useHeader) url += "&key=" + URLEncoder.encode(key, "UTF-8")
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (useHeader) conn.setRequestProperty("x-goog-api-key", key)
            val body = buildBody(userText, hist, model, withThinking)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val resp = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                var msg = try {
                    JSONObject(resp).optJSONObject("error")?.optString("message", "") ?: ""
                } catch (e: Exception) { "" }
                if (msg.isBlank()) msg = resp
                return StreamError(code, msg.take(200))
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val j = try { JSONObject(payload) } catch (e: Exception) { continue }
                    val er = j.optJSONObject("error")
                    if (er != null) {
                        return StreamError(er.optInt("code", 500), er.optString("message", "").take(200))
                    }
                    val text = extractText(j)
                    if (text.isNotEmpty()) {
                        streamed[0] = true
                        mainHandler.post { if (turn == turnId) curReply?.onText(text) }
                    }
                }
            }
            return if (streamed[0]) null else StreamError(200, "جواب خالی ملا")
        } catch (e: Exception) {
            return StreamError(0, "نیٹ ورک خرابی: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200))
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractText(j: JSONObject): String {
        val parts = j.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text", ""))
        }
        return sb.toString()
    }

    private fun msg(role: String, text: String): JSONObject {
        return JSONObject().put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
    }

    private fun buildBody(
        userText: String, hist: List<Pair<String, String>>, model: String, withThinking: Boolean
    ): String {
        val root = JSONObject()
        root.put("systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
        val contents = JSONArray()
        for ((u, r) in hist) {
            contents.put(msg("user", u))
            contents.put(msg("model", r))
        }
        contents.put(msg("user", userText))
        root.put("contents", contents)
        val gc = JSONObject().put("maxOutputTokens", 250).put("temperature", 0.3)
        if (withThinking) {
            if (model.startsWith("gemini-2.5")) {
                gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            } else if (model.startsWith("gemini-3")) {
                gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
            }
        }
        root.put("generationConfig", gc)
        return root.toString()
    }

    // ---------- ایکشنز (nullکا مطلب کامیاب) ----------
    private fun performAction(action: String, target: String): String? {
        return when (action) {
            "TORCH_ON" -> if (setTorch(true)) null else "ٹارچ آن نہیں ہو سکی"
            "TORCH_OFF" -> if (setTorch(false)) null else "ٹارچ بند نہیں ہو سکی"
            "OPEN_WHATSAPP" -> openApp(listOf("com.whatsapp", "com.whatsapp.w4b"), "https://wa.me/", "واٹس ایپ نہیں کھل سکا")
            "OPEN_YOUTUBE" -> openApp(listOf("com.google.android.youtube"), "https://www.youtube.com", "یوٹیوب نہیں کھل سکا")
            "OPEN_TIKTOK" -> openApp(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com", "ٹک ٹاک نہیں کھل سکا")
            "OPEN_FACEBOOK" -> openApp(listOf("com.facebook.katana"), "https://www.facebook.com", "فیس بک نہیں کھل سکا")
            "OPEN_CAMERA" -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "کیمرہ نہیں کھل سکا")
            "OPEN_SETTINGS" -> launch(Intent(Settings.ACTION_SETTINGS), "سیٹنگز نہیں کھل سکیں")
            "OPEN_WIFI" -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), "وائی فائی سیٹنگز نہیں کھل سکیں")
            "OPEN_BLUETOOTH" -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "بلوٹوتھ سیٹنگز نہیں کھل سکیں")
            "TIME" -> timeReply()
            "CALL" -> makeCall(target)
            else -> null
        }
    }

    private fun launch(intent: Intent, failReply: String): String? {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            null
        } catch (e: Exception) {
            failReply
        }
    }

    private fun openApp(pkgs: List<String>, fallbackUrl: String, failReply: String): String? {
        for (p in pkgs) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) return launch(i, failReply)
        }
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)), failReply)
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

    private fun makeCall(target: String): String? {
        if (target.isBlank()) return "کس کو کال کرنی ہے؟ نام بتائیں"
        val isNumber = target.matches(Regex("[+0-9 ()-]{5,}"))
        if (!isNumber && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "رابطوں کی اجازت نہیں ہے، ایپ کو اجازت دیں"
        }
        val number = if (isNumber) target.filter { it.isDigit() || it == '+' } else findNumber(target)
        if (number.isNullOrEmpty()) return "یہ نام رابطوں میں نہیں ملا"
        val canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val act = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return launch(Intent(act, Uri.parse("tel:" + Uri.encode(number))), "کال نہیں ہو سکی")
    }
}
