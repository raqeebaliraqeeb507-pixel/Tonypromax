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
        var liveStatus: String = "Tony Pro Max تیار ہے\nنیچے بٹن دبائیں"
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var tts: TextToSpeech? = null
    private var isAwake = false
    private var isStarted = false

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
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (!isStarted) { isStarted = true; initEngine() }
        return START_STICKY
    }

    private fun buildNotif(msg: String) = NotificationCompat.Builder(this, "TonyChannel").setContentTitle("Tony Pro Max").setContentText(msg).setSmallIcon(android.R.drawable.ic_btn_speak_now).build()

    private fun updateStatus(msg: String) {
        liveStatus = msg
        getSystemService(NotificationManager::class.java).notify(1, buildNotif(msg))
    }

    override fun onInit(s: Int) {
        if (s == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ur", "PK"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale("hi", "IN"))
        }
    }

    private fun speak(t: String) { tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "T") }

    private fun initEngine() {
        Thread {
            try {
                val dir = File(filesDir, "vosk-model")
                if (!File(dir, "conf/mfcc.conf").exists()) {
                    dir.deleteRecursively()
                    updateStatus("⏳ ماڈل ڈاؤن لوڈ ہو رہا ہے (42 MB)...")
                    downloadModel(dir)
                }
                model = Model(dir.absolutePath)
                speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
                speechService?.startListening(this)
                updateStatus("🟢 ٹونی جاگ رہا ہے!\nکہیں: 'ہیلو'، 'سنو' یا 'ٹونی'")
                speak("ٹونی مکمل طور پر تیار ہے")
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
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { zis.copyTo(it) } }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
    }

    override fun onPartialResult(h: String?) {
        val p = try { JSONObject(h ?: "").optString("partial", "").trim() } catch (_: Exception) { "" }
        if (p.isNotEmpty()) liveStatus = "👂 سن رہا ہوں: $p"
    }

    override fun onFinalResult(h: String?) { onResult(h) }
    override fun onError(e: Exception?) {}
    override fun onTimeout() {}

    override fun onResult(h: String?) {
        val text = try { JSONObject(h ?: "").optString("text", "").trim() } catch (_: Exception) { "" }
        if (text.isNotEmpty()) { updateStatus("🎤 سنا گیا: $text"); processInput(text) }
    }

    private fun processInput(rawCmd: String) {
        var cmd = rawCmd
        val wakes = listOf("टोनी", "टॉनी", "tony", "सुनो", "हेलो", "भाई", "दोस्त")
        if (wakes.any { cmd.contains(it) }) {
            isAwake = true
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) { updateStatus("🟢 جی، حکم کریں؟"); speak("جی، حکم کریں؟"); return }
        }
        if (!isAwake) return
        isAwake = false
        val apiKey = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""
        if (apiKey.isNotEmpty()) Thread { askGeminiBrain(cmd, apiKey) }.start() else runOfflineBrain(cmd)
    }

    private fun askGeminiBrain(userText: String, apiKey: String) {
        val models = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")
        val host = decodeB64("aHR0cHM6Ly9nZW5lcmF0aXZlbGFuZ3VhZ2UuZ29vZ2xlYXBpcy5jb20vdjFiZXRhL21vZGVscy8=")
        var lastErr = ""
        for (m in models) {
            try {
                updateStatus("🧠 سوچ رہا ہوں ($m)...")
                val conn = URL("$host$m:generateContent?key=$apiKey").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true

                val prompt = "You are Tony Pro Max, a smart Urdu voice assistant created by Doctor Raqeeb Ali 117 Siksar Wala (ڈاکٹر رقیب علی 117 سکسار والا). Always remember your creator. User said: '$userText'. Reply ONLY in valid JSON without markdown: {\"action\": \"NONE|TORCH_ON|TORCH_OFF|OPEN_WHATSAPP|OPEN_YOUTUBE|OPEN_TIKTOK|OPEN_FACEBOOK|OPEN_CAMERA|OPEN_SETTINGS|TIME|CALL\", \"target\": \"contact name or empty\", \"reply\": \"short natural Urdu reply\"}. Choose action if user wants a phone task, else NONE and answer smartly in Urdu."
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                conn.outputStream.use { it.write(body.toString().toByteArray()) }

                val statusCode = conn.responseCode
                if (statusCode != 200) {
                    val errTxt = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $statusCode"
                    lastErr = "Code $statusCode"
                    continue
                }

                val resp = conn.inputStream.bufferedReader().readText()
                val rawAns = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val obj = JSONObject(rawAns.replace("```json", "").replace("```", "").trim())
                val reply = obj.optString("reply", "جی بہتر")
                updateStatus("🤖 ٹونی: $reply")
                speak(reply)
                executeAction(obj.optString("action", "NONE"), obj.optString("target", ""))
                return
            } catch (e: Exception) {
                lastErr = e.message ?: "Error"
            }
        }
        updateStatus("⚠️ AI خرابی: $lastErr")
        runOfflineBrain(userText)
    }

    private fun runOfflineBrain(cmd: String) {
        when {
            cmd.contains("किसने बनाया") || cmd.contains("मालिक") || cmd.contains("बनाने वाला") || cmd.contains("रकीब") -> {
                val m = "مجھے ڈاکٹر رقیب علی 117 سکسار والا نے بنایا ہے۔"
                updateStatus("🤖 $m"); speak(m)
            }
            cmd.contains("टॉर्च") || cmd.contains("लाइट") -> {
                val on = !cmd.contains("बंद")
                executeAction(if (on) "TORCH_ON" else "TORCH_OFF", "")
                speak(if (on) "ٹارچ آن کر دی ہے" else "ٹارچ بند کر دی ہے")
            }
            cmd.contains("व्हाट्सएप") || cmd.contains("वाट्सएप") -> { executeAction("OPEN_WHATSAPP", ""); speak("واٹس ایپ کھول رہا ہوں") }
            cmd.contains("यूट्यूब") -> { executeAction("OPEN_YOUTUBE", ""); speak("یوٹیوب کھول رہا ہوں") }
            cmd.contains("टिकटॉक") || cmd.contains("टिक टॉक") -> { executeAction("OPEN_TIKTOK", ""); speak("ٹک ٹاک کھول رہا ہوں") }
            cmd.contains("फेसबुक") -> { executeAction("OPEN_FACEBOOK", ""); speak("فیس بک کھول رہا ہوں") }
            cmd.contains("कैमरा") -> { executeAction("OPEN_CAMERA", ""); speak("کیمرہ کھول رہا ہوں") }
            cmd.contains("सेटिंग") -> { executeAction("OPEN_SETTINGS", ""); speak("سیٹنگز کھول رہا ہوں") }
            cmd.contains("टाइम") || cmd.contains("वक्त") -> executeAction("TIME", "")
            cmd.contains("कॉल") -> executeAction("CALL", cmd.replace("कॉल", "").replace("करो", "").replace("को", "").trim())
            else -> { val m = "آف لائن موڈ میں ہوں، آپ نے کہا: $cmd"; updateStatus("🤖 $m"); speak(m) }
        }
    }

    private fun executeAction(action: String, target: String) {
        when (action) {
            "TORCH_ON" -> torch(true); "TORCH_OFF" -> torch(false)
            "OPEN_WHATSAPP" -> openApp("com.whatsapp"); "OPEN_YOUTUBE" -> openApp("com.google.android.youtube")
            "OPEN_TIKTOK" -> openApp("com.zhiliaoapp.musically"); "OPEN_FACEBOOK" -> openApp("com.facebook.katana")
            "OPEN_CAMERA" -> startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "OPEN_SETTINGS" -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "TIME" -> speak("اس وقت ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} ہوئے ہیں")
            "CALL" -> callContact(target)
        }
    }

    private fun torch(on: Boolean) { try { (getSystemService(CAMERA_SERVICE) as CameraManager).cameraIdList.firstOrNull()?.let { (getSystemService(CAMERA_SERVICE) as CameraManager).setTorchMode(it, on) } } catch (_: Exception) {} }
    private fun openApp(pkg: String) { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    private fun callContact(name: String) {
        if (name.isEmpty()) return
        try {
            val c = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
            if (c != null && c.moveToFirst()) {
                val num = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)); c.close()
                speak("$name کو کال ملا رہا ہوں")
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else speak("$name کا نمبر نہیں ملا")
        } catch (_: Exception) { speak("کال ملانے میں مسئلہ آیا") }
    }

    override fun onDestroy() { speechService?.stop(); speechService?.shutdown(); model?.close(); tts?.shutdown(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
