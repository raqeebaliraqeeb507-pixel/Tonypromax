part2 = '''    private fun processInput(rawCmd: String) {
        var cmd = rawCmd
        val wakes = listOf("टोनी", "टॉनी", "tony", "सुनो", "हेलो", "भाई", "दोस्त")
        if (wakes.any { cmd.contains(it) }) {
            isAwake = true
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) {
                updateStatus("🟢 جی، حکم کریں؟")
                speak("جی، حکم کریں؟")
                return
            }
        }
        if (!isAwake) return
        isAwake = false

        val apiKey = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""
        if (apiKey.isNotEmpty()) {
            Thread { askGeminiBrain(cmd, apiKey) }.start()
        } else {
            runOfflineBrain(cmd)
        }
    }

    private fun askGeminiBrain(userText: String, apiKey: String) {
        try {
            updateStatus("🧠 سوچ رہا ہوں...")
            val base = decodeB64("aHR0cHM6Ly9nZW5lcmF0aXZlbGFuZ3VhZ2UuZ29vZ2xlYXBpcy5jb20vdjFiZXRhL21vZGVscy9nZW1pbmktMS41LWZsYXNoOmdlbmVyYXRlQ29udGVudD9rZXk9")
            val conn = URL(base + apiKey).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val prompt = "You are Tony Pro Max, a smart Urdu voice assistant created by Doctor Raqeeb Ali 117 Siksar Wala (ڈاکٹر رقیب علی 117 سکسار والا). Always remember your creator. User said: '$userText'. Reply ONLY in valid JSON without markdown: {\\"action\\": \\"NONE|TORCH_ON|TORCH_OFF|OPEN_WHATSAPP|OPEN_YOUTUBE|OPEN_TIKTOK|OPEN_FACEBOOK|OPEN_CAMERA|OPEN_SETTINGS|TIME|CALL\\", \\"target\\": \\"contact name or empty\\", \\"reply\\": \\"short natural Urdu reply\\"}. Choose action if user wants a phone task, else NONE and answer smartly in Urdu."
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val resp = conn.inputStream.bufferedReader().readText()
            val rawAns = JSONObject(resp).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val cleanJson = rawAns.replace("```json", "").replace("```", "").trim()
            val obj = JSONObject(cleanJson)
            val action = obj.optString("action", "NONE")
            val target = obj.optString("target", "")
            val reply = obj.optString("reply", "جی بہتر")

            updateStatus("🤖 ٹونی: $reply")
            speak(reply)
            executeAction(action, target)
        } catch (e: Exception) {
            runOfflineBrain(userText)
        }
    }

    private fun runOfflineBrain(cmd: String) {
        when {
            cmd.contains("किसने बनाया") || cmd.contains("मालिक") || cmd.contains("बनाने वाला") || cmd.contains("रकीब") -> {
                val creatorMsg = "مجھے ڈاکٹر رقیب علی 117 سکسار والا نے بنایا ہے۔"
                updateStatus("🤖 $creatorMsg")
                speak(creatorMsg)
            }
            cmd.contains("टॉर्च") || cmd.contains("लाइट") || cmd.contains("फ्लैश") -> {
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
            cmd.contains("टाइम") || cmd.contains("समय") || cmd.contains("वक्त") -> executeAction("TIME", "")
            cmd.contains("कॉल") || cmd.contains("फोन लगा") -> {
                val name = cmd.replace("कॉल", "").replace("करो", "").replace("को", "").trim()
                executeAction("CALL", name)
            }
            else -> {
                val msg = "آف لائن موڈ میں ہوں، آپ نے کہا: $cmd"
                updateStatus("🤖 $msg")
                speak(msg)
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
            "TIME" -> speak("اس وقت ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} ہوئے ہیں")
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
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun callContact(name: String) {
        if (name.isEmpty()) return
        try {
            val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
            if (cursor != null && cursor.moveToFirst()) {
                val num = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                cursor.close()
                speak("$name کو کال ملا رہا ہوں")
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                speak("$name کا نمبر فون بک میں نہیں ملا")
            }
        } catch (_: Exception) { speak("کال ملانے میں مسئلہ آیا") }
    }

    override fun onDestroy() { speechService?.stop(); speechService?.shutdown(); model?.close(); tts?.shutdown(); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
'''

with open("app/src/main/java/com/tony/promax/TonyService.kt", "w") as f:
    f.write(part1 + "\n" + part2)
print("✅ ALL_COMPLETE_WITH_CREATOR")
