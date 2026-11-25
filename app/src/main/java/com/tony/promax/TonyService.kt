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
