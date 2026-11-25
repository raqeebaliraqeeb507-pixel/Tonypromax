package com.tony.promax

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val statusText = TextView(this).apply {
            text = "Tony Pro Max تیار ہے"
            textSize = 22f
            setPadding(0, 0, 0, 40)
        }

        val startBtn = Button(this).apply {
            text = "اسسٹنٹ آن کریں (Start Tony)"
            setOnClickListener {
                checkPermissionsAndStart(statusText)
            }
        }

        layout.addView(statusText)
        layout.addView(startBtn)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart(statusText: TextView) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            statusText.text = "اجازت (Permission) دے کر دوبارہ بٹن دبائیں"
        } else {
            val intent = Intent(this, TonyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "🟢 Tony بیک گراؤنڈ میں جاگ رہا ہے!"
        }
    }
}
