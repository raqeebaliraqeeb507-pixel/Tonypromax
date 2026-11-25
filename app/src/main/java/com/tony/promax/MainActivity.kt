package com.tony.promax

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }
        statusText = TextView(this).apply {
            text = TonyService.liveStatus
            textSize = 20f
            setPadding(0, 0, 0, 50)
        }
        val startBtn = Button(this).apply {
            text = "اسسٹنٹ آن کریں (START TONY)"
            setOnClickListener { checkPermissionsAndStart() }
        }
        layout.addView(statusText)
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
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
            TonyService.liveStatus = "اجازت دے کر دوبارہ بٹن دبائیں"
        } else {
            val i = Intent(this, TonyService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
    }
}
