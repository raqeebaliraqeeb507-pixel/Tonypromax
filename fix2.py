import re

path = "app/src/main/java/com/tony/promax/TonyService.kt"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. Remove Hindi from UI status lines
code = re.sub(r'liveStatus\s*=\s*"[^"]*सुन रहा[^"]*"', 'liveStatus = "🎙️ آپ کی آواز سن رہا ہوں..."', code)
code = re.sub(r'updateStatus\("[^"]*सुना गया[^"]*"\)', 'updateStatus("🧠 بات سمجھ رہا ہوں...")', code)
code = re.sub(r'updateStatus\("[^"]*हुक्म करें[^"]*"\)', 'updateStatus("🟢 جی ڈاکٹر صاحب، حکم کریں؟")', code)
code = re.sub(r'speak\("[^"]*हुक्म करें[^"]*"\)', 'speak("جی ڈاکٹر صاحب، حکم کریں؟")', code)

# 2. Prevent self-echo & add 25s active listening
code = code.replace("private var isAwake = false", "private var awakeUntil: Long = 0L")
code = re.sub(
    r'if\s*\(\s*wakes\.any\s*\{[^}]+\}\s*\)\s*\{[^}]+\}\s*if\s*\(\s*!isAwake\s*\)\s*return\s*isAwake\s*=\s*false',
    '''if (tts?.isSpeaking == true) return
        val now = System.currentTimeMillis()
        if (wakes.any { cmd.contains(it) }) {
            awakeUntil = now + 25000L
            wakes.forEach { cmd = cmd.replace(it, "").trim() }
            if (cmd.isEmpty()) { updateStatus("🟢 جی ڈاکٹر صاحب، حکم کریں؟"); speak("جی ڈاکٹر صاحب، حکم کریں؟"); return }
        } else if (now > awakeUntil) {
            return
        }
        awakeUntil = now + 25000L''',
    code,
    flags=re.DOTALL
)

# 3. Update Gemini models & enforce pure Urdu reply
code = re.sub(r'val models = listOf\([^\)]+\)', 'val models = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")', code)
code = code.replace('short natural Urdu reply', 'short natural reply ONLY in pure Urdu script (اردو), NEVER use Hindi Devanagari letters')

with open(path, "w", encoding="utf-8") as f:
    f.write(code)

print("✅ فائل اپ ڈیٹ ہو گئی!")
