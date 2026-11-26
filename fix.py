path = "app/src/main/java/com/tony/promax/TonyService.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Screen se Hindi khatam karke pure Urdu karna
code = code.replace('liveStatus = "👂 सुन रहा हूँ: $p"', 'liveStatus = "🎙️ آپ کی آواز سن رہا ہوں..."')
code = code.replace('updateStatus("💬 सुना गया: $text")', 'updateStatus("🧠 بات سمجھ رہا ہوں...")')

# 2. 25 second lagatar baat sunna aur apni awaz (Echo) se bachna
code = code.replace("private var isAwake = false", "private var awakeUntil: Long = 0L")

old_wake = '''        if (wakes.any { cmd.contains(it) }) {
            isAwake = true
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) { updateStatus("🟢 जी, हुक्म करें!"); speak("जी, हुक्म करें"); return }
        }
        if (!isAwake) return
        isAwake = false'''

new_wake = '''        if (tts?.isSpeaking == true) return
        val now = System.currentTimeMillis()
        if (wakes.any { cmd.contains(it) }) {
            awakeUntil = now + 25000L
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) { updateStatus("🟢 جی ڈاکٹر صاحب، حکم کریں؟"); speak("جی ڈاکٹر صاحب، حکم کریں؟"); return }
        } else if (now > awakeUntil) {
            return
        }
        awakeUntil = now + 25000L'''

code = code.replace(old_wake, new_wake)

# 3. Gemini models aur khalis Urdu jawab
code = code.replace(
    'val models = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")',
    'val models = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")'
)
code = code.replace(
    'short natural Urdu reply',
    'short natural reply ONLY in pure Urdu script (اردو), NEVER use Hindi Devanagari letters'
)

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ تمام تبدیلیاں مکمل ہو گئیں!")
