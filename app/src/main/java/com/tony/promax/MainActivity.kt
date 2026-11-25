package com.tony.promax

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            statusText.text = TonyService.liveStatus
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("TonyPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        statusText = TextView(this).apply {
            text = TonyService.liveStatus
            textSize = 18f
            setPadding(0, 0, 0, 40)
        }

        val apiInput = EditText(this).apply {
            hint = "یہاں Gemini API Key پیسٹ کریں (اختیاری)"
            setText(prefs.getString("api_key", ""))
        }

        val saveBtn = Button(this).apply {
            text = "API Key محفوظ کریں"
            setOnClickListener {
                prefs.edit().putString("api_key", apiInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "✅ دماغ کی چابی محفوظ ہو گئی!", Toast.LENGTH_SHORT).show()
            }
        }

        val startBtn = Button(this).apply {
            text = "اسسٹنٹ آن کریں (START TONY)"
            setOnClickListener { checkPermissionsAndStart() }
        }

        layout.addView(statusText)
        layout.addView(apiInput)
        layout.addView(saveBtn)
        layout.addView(startBtn)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        handler.post(updater)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updater)
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            TonyService.liveStatus = "تمام اجازتیں (Permissions) دے کر دوبارہ بٹن دبائیں"
        } else {
            val i = Intent(this, TonyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}
