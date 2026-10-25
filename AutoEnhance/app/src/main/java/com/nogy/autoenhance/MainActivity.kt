package com.nogy.autoenhance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        val title = TextView(this).apply {
            text = "Auto Enhance"
            textSize = 28f
        }
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val startBtn = Button(this).apply {
            text = "Start / Fix permissions"
            setOnClickListener { begin() }
        }
        val batteryBtn = Button(this).apply {
            text = "Allow background running (important)"
            setOnClickListener { askBattery() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(startBtn)
        root.addView(batteryBtn)
        setContentView(root)

        begin()
    }

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun begin() {
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, PhotoWatcherService::class.java))
        status.text = "Running. Click a photo with your camera app.\n" +
            "Enhanced copies appear in Pictures/Enhanced (album \"Enhanced\")."
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = neededPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) begin()
        else status.text = "Permission needed. Choose \"Allow all\" for photos, then tap Start again."
    }

    private fun askBattery() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        )
    }
}
