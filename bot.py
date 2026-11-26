import os, json, urllib.request, base64

order = open("order.txt", encoding="utf-8").read()
code = open("app/src/main/java/com/tony/promax/TonyService.kt", encoding="utf-8").read()
prompt = f"Update this Kotlin Android code according to this instruction: {order}. Return ONLY valid Kotlin code without markdown.\n\n{code}"

host = base64.b64decode("aHR0cHM6Ly9nZW1pbmlhcGkuZ29vZ2xlYXBpcy5jb20vdjFiZXRhL21vZGVscy9nZW1pbmktMi41LWZsYXNoOmdlbWVyYXRlQ29udGVudA==").decode()
host = host.replace("gemerateContent", "generateContent")
api_key = os.environ["API_KEY"].strip()

req = urllib.request.Request(
    host,
    data=json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode("utf-8"),
    headers={"Content-Type": "application/json", "x-goog-api-key": api_key}
)
res = json.loads(urllib.request.urlopen(req).read().decode("utf-8"))
new_code = res["candidates"][0]["content"]["parts"][0]["text"].replace("```kotlin", "").replace("```", "").strip()
open("app/src/main/java/com/tony/promax/TonyService.kt", "w", encoding="utf-8").write(new_code)
print("✅ AI updated TonyService.kt successfully!")
