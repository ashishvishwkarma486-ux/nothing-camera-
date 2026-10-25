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
            hint = "Image model (optional, empty = automatic best)"
            setSingleLine(true)
        }
        val promptBox = EditText(this).apply {
            hint = "Your own prompt (optional, empty = built-in prompt)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        val saveBtn = Button(this).apply {
            text = "Save"
            setOnClickListener {
                val k = keyBox.text.toString().trim()
                val m = modelBox.text.toString().trim()
                val p = promptBox.text.toString().trim()
                if (k.isNotEmpty()) prefs.edit().putString("gemini_key", k).apply()
                if (m.isNotEmpty()) prefs.edit().putString("image_model", m).apply()
                if (p.isNotEmpty()) prefs.edit().putString("prompt", p).apply()
                keyBox.setText("")
                modelBox.setText("")
                promptBox.setText("")
                refreshAi()
            }
        }
        val resetBtn = Button(this).apply {
            text = "Reset model and prompt to automatic"
            setOnClickListener {
                prefs.edit().remove("image_model").remove("prompt").apply()
                refreshAi()
            }
        }
        val removeKey = Button(this).apply {
            text = "Remove key (stops everything)"
            setOnClickListener {
                prefs.edit().remove("gemini_key").apply()
                refreshAi()
            }
        }

        root.addView(title)
        root.addView(status)
        root.addView(startBtn)
        root.addView(batteryBtn)
        root.addView(aiStatus)
        root.addView(keyBox)
        root.addView(modelBox)
        root.addView(promptBox)
        root.addView(saveBtn)
        root.addView(resetBtn)
        root.addView(removeKey)
        setContentView(ScrollView(this).apply { addView(root) })

        begin()
    }

    override fun onResume() {
        super.onResume()
        refreshAi()
    }

    private fun refreshAi() {
        val has = !prefs.getString("gemini_key", "").isNullOrEmpty()
        val model = prefs.getString("image_model", "").orEmpty().ifEmpty { "automatic (best available)" }
        val prompt = if (prefs.getString("prompt", "").isNullOrEmpty()) "built-in" else "your own"
        val last = prefs.getString("last_result", null)
        val base = if (has)
            "Gemini: ON. Each new camera photo is sent whole (location removed) to Google. " +
                "Gemini's finished photo is saved as AI_ in Pictures/Enhanced. " +
                "Your original is never changed.\nModel: $model | Prompt: $prompt"
        else
            "Gemini: OFF. Paste your key below and tap Save to start."
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
            "Wait about 1 minute, then check the Enhanced album.\n" +
            "If you delete the original, its AI_ copy is deleted too."
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
