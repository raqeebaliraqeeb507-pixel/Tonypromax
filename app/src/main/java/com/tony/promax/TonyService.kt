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
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.RingtoneManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TonyService : Service(), TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        var liveStatus: String = "ٹونی بند ہے"
        private const val CHANNEL_ID = "tony_channel"
        private const val NOTIF_ID = 117
        private const val SLEEP_MS = 12000L
        private const val MUTE_BEEP = true
        private val GEMINI_MODELS = listOf(
            "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash"
        )
        private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
        private val ALLOWED_ACTIONS = setOf(
            "NONE", "TORCH_ON", "TORCH_OFF", "OPEN_WHATSAPP", "OPEN_YOUTUBE", "OPEN_TIKTOK",
            "OPEN_FACEBOOK", "OPEN_CAMERA", "OPEN_SETTINGS", "OPEN_WIFI", "OPEN_BLUETOOTH",
            "TIME", "CALL", "OPEN_APP"
        )
        private val ALIASES = mapOf(
            "fb" to "facebook", "yt" to "youtube", "insta" to "instagram", "whatsup" to "whatsapp",
            "watsapp" to "whatsapp", "chrom" to "chrome", "calc" to "calculator", "tiktok" to "tiktok"
        )
        private val WAKE_REGEXES = listOf(
            Regex("(بات\\s*)?(سنو|سنتے\\s*ہو)\\s*(ٹونی|ٹوني|ٹانی|ٹونے|ٹنی|tony|toni|tonny)"),
            Regex("(baat\\s*)?(suno|sunno|sunte\\s*ho)\\s*(tony|toni|tonny)")
        )
        private const val SYSTEM_PROMPT =
            "Your name is Tony Pro Max. Stay in this identity always. Never mention Claude, Anthropic, Gemini or Google as who you are or who made you. If anyone asks who created or made you, answer exactly: مجھے ڈاکٹر رقیب علی نے بنایا ہے۔ If anyone asks where Dr. Raqeeb Ali lives, answer exactly: وہ ضلع بہاولنگر، تحصیل ہارون آباد، چک نمبر 117/6R میں رہتے ہیں۔ (Still begin with the [NONE] tag line as required.) " +
            "You are Tony, a personal AI assistant. Important facts: Dr. Raqeeb Ali (ڈاکٹر رقیب علی) " +
            "lives in 117/6-R, District Bahawalnagar, Pakistan (117 سکس آر، ضلع بہاولنگر). " +
            "Always answer concisely in 1-2 sentences in Urdu. Never guess or make up personal facts; " +
            "if you don't know something, simply say so. " +
            "Output format: the very first line of every answer must be a tag in square brackets. " +
            "Use [NONE] for normal chat and questions. Use one of [TORCH_ON] [TORCH_OFF] [OPEN_WHATSAPP] " +
            "[OPEN_YOUTUBE] [OPEN_TIKTOK] [OPEN_FACEBOOK] [OPEN_CAMERA] [OPEN_SETTINGS] [OPEN_WIFI] " +
            "[OPEN_BLUETOOTH] [TIME] for device commands. Use [OPEN_APP:Name] to open ANY other app, " +
            "where Name is the app name in English letters (for example [OPEN_APP:Chrome]). " +
            "Use [CALL:Name] to phone someone, where " +
            "Name is the contact name in English letters. After the tag, on a new line, write the Urdu reply " +
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
    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val lastErr = ConcurrentHashMap<String, String>()

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
    private var errStreak = 0
    private var quickFails = 0
    private var lastStartAt = 0L
    private var beepMuted = false
    @Volatile private var online = true
    private var inCall = false
    private var phoneCb: Any? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    // ---------- اردو اسٹیٹس ----------
    private fun setStatus(s: String) {
        val clean = DEVANAGARI.replace(s, "").trim()
        liveStatus = if (clean.isEmpty()) "..." else clean
    }

    private fun stateText(): String = stateBase() + (if (online) "" else " — آف لائن موڈ")

    private fun stateBase(): String {
        return if (isAwake) "ٹونی جاگ رہا ہے — اپنی بات بولیں"
        else "ٹونی سٹینڈ بائی — 'بات سنو ٹونی' کہیں"
    }

    // ---------- مائیک کی بیپ خاموش ----------
    private fun muteBeep(mute: Boolean) {
        if (!MUTE_BEEP || beepMuted == mute) return
        beepMuted = mute
        val d = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streams = intArrayOf(
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM
        )
        for (s in streams) {
            try { am.adjustStreamVolume(s, d, 0) } catch (e: Exception) { }
        }
    }

    // ---------- فون کال ----------
    private fun startCallMonitor() {
        if (phoneCb != null) return
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        mainHandler.post { onCallState(state) }
                    }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                phoneCb = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        mainHandler.post { onCallState(state) }
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                phoneCb = l
            }
        } catch (e: Exception) { }
    }

    private fun onCallState(state: Int) {
        if (destroyed) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> onCallRinging()
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (!inCall) {
                    inCall = true
                    cancelSleepTimer()
                    isAwake = false
                    processing = true
                    turnId++
                    stopRecognizer()
                    setStatus("فون کال جاری ہے — مائیک بند")
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (inCall) {
                    inCall = false
                    processing = false
                    muteBeep(false)
                    setStatus(stateText())
                    restartListening(1000)
                }
            }
        }
    }

    private fun onCallRinging() {
        if (inCall) return
        inCall = true
        cancelSleepTimer()
        isAwake = false
        turnId++
        processing = true
        stopRecognizer()
        try { tts?.stop() } catch (e: Exception) { }
        firstChunk = true
        pending = 0
        replyFinished = false
        curReply = null
        setStatus("آپ کی کال آ رہی ہے — مائیک بند")
        try {
            RingtoneManager.getRingtone(
                this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )?.play()
        } catch (e: Exception) { }
        mainHandler.postDelayed({
            if (!inCall || destroyed) return@postDelayed
            speakChunk("آپ کی کال آ رہی ہے ڈاکٹر رقیب علی جی۔")
            replyFinished = true
        }, 1200)
    }

    // ---------- انٹرنیٹ مانیٹر ----------
    private fun startNetMonitor() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { setOnline(true) }
                override fun onLost(network: Network) { setOnline(false) }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (e: Exception) { }
    }

    private fun setOnline(v: Boolean) {
        online = v
        mainHandler.post {
            if (!processing && !holdStatus && !inCall && !destroyed) setStatus(stateText())
        }
    }

    private fun stopMonitors() {
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val pc = phoneCb
            if (pc is PhoneStateListener) {
                @Suppress("DEPRECATION")
                tm.listen(pc, PhoneStateListener.LISTEN_NONE)
            } else if (pc != null && Build.VERSION.SDK_INT >= 31) {
                tm.unregisterTelephonyCallback(pc as TelephonyCallback)
            }
        } catch (e: Exception) { }
        phoneCb = null
        try {
            val cb = netCb
            if (cb != null) {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            }
        } catch (e: Exception) { }
        netCb = null
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
        startNetMonitor()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCallMonitor()
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
        stopMonitors()
        mainHandler.removeCallbacksAndMessages(null)
        muteBeep(false)
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }
    }

    private val startRunnable = Runnable {
        if (destroyed || processing || listening || inCall) return@Runnable
        val r = recognizer ?: return@Runnable
        try {
            listening = true
            lastStartAt = SystemClock.elapsedRealtime()
            muteBeep(true)
            r.startListening(recIntent)
        } catch (e: Exception) {
            listening = false
            muteBeep(false)
            errStreak++
            recreateRecognizer()
            restartListening(minOf(8000L, 1000L * errStreak))
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
        if (inCall) return
        mainHandler.removeCallbacks(startRunnable)
        mainHandler.postDelayed(startRunnable, delay)
    }

    private fun stopRecognizer() {
        listening = false
        mainHandler.removeCallbacks(startRunnable)
        muteBeep(false)
        try { recognizer?.cancel() } catch (e: Exception) { }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!processing && !holdStatus) setStatus(stateText())
        }
        override fun onBeginningOfSpeech() {
            errStreak = 0
            quickFails = 0
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
            errStreak = 0
            quickFails = 0
            if (processing) return
            val list: List<String> =
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
            handleSpeech(list)
            if (!processing) restartListening(300)
        }
        override fun onError(error: Int) {
            listening = false
            if (processing) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH && isAwake) armSleepTimer()
                    val ran = SystemClock.elapsedRealtime() - lastStartAt
                    if (ran < 2500L) quickFails++ else quickFails = 0
                    val wait = if (quickFails == 0) 300L else minOf(5000L, 700L * quickFails)
                    restartListening(wait)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                    recreateRecognizer()
                    errStreak++
                    restartListening(minOf(8000L, 1000L * errStreak))
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    muteBeep(false)
                    setStatus("مائیکروفون کی اجازت نہیں ہے")
                }
                else -> {
                    errStreak++
                    setStatus("سننے میں خرابی (کوڈ ${error}) — دوبارہ کوشش ہو رہی ہے")
                    restartListening(minOf(10000L, 1500L * errStreak))
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

    // ---------- آف لائن موڈ (مقامی جوابات) ----------
    private fun offlineAnswer(q: String) {
        val n = normalize(q)
        fun has(vararg w: String) = w.any { n.contains(it) }
        var tag = "NONE"
        var text = "انٹرنیٹ بند ہے، میں اس وقت صرف بنیادی کام کر سکتا ہوں، جیسے وقت بتانا، ٹارچ اور ایپس کھولنا۔"
        when {
            has("کس نے بنایا", "بنانے والا", "تمہیں کس", "آپ کو کس", "خالق") ->
                text = "مجھے ڈاکٹر رقیب علی نے بنایا ہے۔"
            has("کہاں رہتے", "کہاں رہتا", "رہائش") ->
                text = "وہ ضلع بہاولنگر، تحصیل ہارون آباد، چک نمبر 117/6R میں رہتے ہیں۔"
            has("تمہارا نام", "آپ کا نام", "کون ہو", "کون ہیں", "نام کیا") ->
                text = "میرا نام ٹونی پرو میکس ہے۔"
            has("وقت", "ٹائم", "بجے") -> tag = "TIME"
            has("ٹارچ", "فلیش", "لائٹ") ->
                tag = if (has("بند", "آف")) "TORCH_OFF" else "TORCH_ON"
            has("واٹس") -> tag = "OPEN_WHATSAPP"
            has("یوٹیوب") -> tag = "OPEN_YOUTUBE"
            has("ٹک ٹاک", "ٹکٹاک") -> tag = "OPEN_TIKTOK"
            has("فیس بک") -> tag = "OPEN_FACEBOOK"
            has("کیمرہ", "کیمرا") -> tag = "OPEN_CAMERA"
            has("وائی فائی", "وائفائی") -> tag = "OPEN_WIFI"
            has("بلوٹوتھ", "بلوٹوث") -> tag = "OPEN_BLUETOOTH"
            has("سیٹنگ") -> tag = "OPEN_SETTINGS"
        }
        val r = Reply(q)
        curReply = r
        r.onText("[" + tag + "]\n" + (if (tag == "NONE") text else ""))
        r.finish()
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
        if (!online) { offlineAnswer(q); return }
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
            if (System.currentTimeMillis() < (coolUntil[m] ?: 0L)) {
                errors.add("${m} — " + (lastErr[m] ?: "وقفہ"))
                continue
            }
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
                val cool = when (err.code) {
                    404 -> 3600000L
                    429 -> 60000L
                    500, 503 -> 20000L
                    else -> 0L
                }
                if (cool > 0) coolUntil[m] = System.currentTimeMillis() + cool
                lastErr[m] = label + err.text
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
        mainHandler.post {
            if (turn == turnId) {
                if (netFails > 0 && netFails == errors.size) offlineAnswer(userText)
                else failTurn("جیمنی خرابی:\n" + msg)
            }
        }
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

    // ---------- ایکشنز (null کا مطلب کامیاب) ----------
    private fun performAction(action: String, target: String): String? {
        return when (action) {
            "TORCH_ON" -> if (setTorch(true)) null else "ٹارچ آن نہیں ہو سکی"
            "TORCH_OFF" -> if (setTorch(false)) null else "ٹارچ بند نہیں ہو سکی"
            "OPEN_WHATSAPP" -> openApp(listOf("com.whatsapp", "com.whatsapp.w4b"), "whatsapp", "https://wa.me/", "واٹس ایپ نہیں کھل سکا")
            "OPEN_YOUTUBE" -> openApp(listOf("com.google.android.youtube"), "youtube", "https://www.youtube.com", "یوٹیوب نہیں کھل سکا")
            "OPEN_TIKTOK" -> openApp(listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "tiktok", "https://www.tiktok.com", "ٹک ٹاک نہیں کھل سکا")
            "OPEN_FACEBOOK" -> openApp(listOf("com.facebook.katana"), "facebook", "https://www.facebook.com", "فیس بک نہیں کھل سکا")
            "OPEN_CAMERA" -> launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "کیمرہ نہیں کھل سکا")
            "OPEN_SETTINGS" -> launch(Intent(Settings.ACTION_SETTINGS), "سیٹنگز نہیں کھل سکیں")
            "OPEN_WIFI" -> launch(Intent(Settings.ACTION_WIFI_SETTINGS), "وائی فائی سیٹنگز نہیں کھل سکیں")
            "OPEN_BLUETOOTH" -> launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "بلوٹوتھ سیٹنگز نہیں کھل سکیں")
            "OPEN_APP" -> openByName(target)
            "TIME" -> timeReply()
            "CALL" -> makeCall(target)
            else -> null
        }
    }

    private fun launch(intent: Intent, failReply: String): String? {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this))
                "کوشش کی ہے۔ اگر ایپ نہ کھلے تو ٹونی کو دوسری ایپس کے اوپر دکھانے کی اجازت دیں"
            else null
        } catch (e: Exception) {
            failReply
        }
    }

    // ---------- انسٹال شدہ ایپس میں نام سے تلاش ----------
    private fun key(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + (if (a[i - 1] == b[j - 1]) 0 else 1))
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun installedApps(): List<Triple<String, String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(i, 0)
        return list.map {
            val label = it.loadLabel(packageManager).toString()
            Triple(key(label), label, it.activityInfo.packageName)
        }
    }

    // واپسی: (ایپ کا نام، پیکج)
    private fun findApp(name: String): Pair<String, String>? {
        var q = key(name)
        if (q.isEmpty()) return null
        q = ALIASES[q] ?: q
        val apps = installedApps()
        apps.firstOrNull { it.first == q }?.let { return Pair(it.second, it.third) }
        apps.firstOrNull { it.first.startsWith(q) }?.let { return Pair(it.second, it.third) }
        apps.firstOrNull { it.first.contains(q) }?.let { return Pair(it.second, it.third) }
        apps.firstOrNull { it.first.length >= 3 && q.contains(it.first) }?.let { return Pair(it.second, it.third) }
        apps.firstOrNull { it.third.lowercase(Locale.ROOT).contains(q) }?.let { return Pair(it.second, it.third) }
        val limit = maxOf(1, q.length / 4)
        var best: Triple<String, String, String>? = null
        var bestD = Int.MAX_VALUE
        for (a in apps) {
            val d = lev(a.first, q)
            if (d <= limit && d < bestD) {
                best = a
                bestD = d
            }
        }
        return best?.let { Pair(it.second, it.third) }
    }

    private fun openByName(name: String): String? {
        if (name.isBlank()) return "کون سی ایپ کھولنی ہے؟ نام بتائیں"
        val found = findApp(name) ?: return "${name} نام کی ایپ فون میں نہیں ملی"
        val i = packageManager.getLaunchIntentForPackage(found.second)
            ?: return "${found.first} کھل نہیں سکی"
        return launch(i, "${found.first} کھل نہیں سکی")
    }

    private fun openApp(pkgs: List<String>, name: String, fallbackUrl: String, failReply: String): String? {
        for (p in pkgs) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) return launch(i, failReply)
        }
        val found = findApp(name)
        if (found != null) {
            val i = packageManager.getLaunchIntentForPackage(found.second)
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
