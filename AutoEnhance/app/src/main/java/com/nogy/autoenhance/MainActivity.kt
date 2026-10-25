package com.nogy.autoenhance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var aiStatus: TextView

    private val prefs by lazy { getSharedPreferences("enhance", MODE_PRIVATE) }

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

        aiStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, pad / 3)
        }
        val keyBox = EditText(this).apply {
            hint = "Paste Gemini API key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val modelBox = EditText(this).apply {
            hint = "AI image model (optional, default: ${GeminiClient.DEFAULT_IMAGE_MODEL})"
            setSingleLine(true)
        }
        val saveBtn = Button(this).apply {
            text = "Save (turn AI on)"
            setOnClickListener {
                val k = keyBox.text.toString().trim()
                val m = modelBox.text.toString().trim()
                if (k.isNotEmpty()) prefs.edit().putString("gemini_key", k).apply()
                if (m.isNotEmpty()) prefs.edit().putString("image_model", m).apply()
                keyBox.setText("")
                modelBox.setText("")
                refreshAi()
            }
        }
        val removeKey = Button(this).apply {
            text = "Remove key (offline mode)"
            setOnClickListener {
                prefs.edit().remove("gemini_key").apply()
                refreshAi()
            }
        }

        val optionsTitle = TextView(this).apply {
            text = "Options"
            textSize = 16f
            setPadding(0, pad, 0, 0)
        }

        root.addView(title)
        root.addView(status)
        root.addView(startBtn)
        root.addView(batteryBtn)
        root.addView(optionsTitle)
        root.addView(option("Skin smoothing (keeps texture)", "skin"))
        root.addView(option("Portrait-style background blur", "bgblur"))
        root.addView(option("Also keep Gemini's own edited copy (AI_ photos)", "save_ai"))
        root.addView(aiStatus)
        root.addView(keyBox)
        root.addView(modelBox)
        root.addView(saveBtn)
        root.addView(removeKey)
        setContentView(ScrollView(this).apply { addView(root) })

        begin()
    }

    private fun option(label: String, prefKey: String): CheckBox {
        val box = CheckBox(this)
        box.text = label
        box.isChecked = prefs.getBoolean(prefKey, true)
        box.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(prefKey, checked).apply()
        }
        return box
    }

    override fun onResume() {
        super.onResume()
        refreshAi()
    }

    private fun refreshAi() {
        val has = !prefs.getString("gemini_key", "").isNullOrEmpty()
        val last = prefs.getString("last_result", null)
        val base = if (has)
            "Gemini: ON. A 1024px copy of each photo (no location data) is sent to Google."
        else
            "Gemini: OFF. Everything runs on your phone, nothing is sent anywhere."
        aiStatus.text = if (last != null) "$base\nLast photo: $last" else base
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
            "Enhanced copies appear in Pictures/Enhanced (album \"Enhanced\").\n" +
            "If you delete the original, its enhanced copy is deleted too."
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
