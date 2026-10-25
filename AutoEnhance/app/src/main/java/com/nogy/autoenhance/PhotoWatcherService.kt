package com.nogy.autoenhance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.Executors

class PhotoWatcherService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            executor.execute { scan() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("enhance", MODE_PRIVATE)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        executor.execute { scan() }
    }

    private fun scan() {
        try {
            syncDeletes()
        } catch (t: Throwable) {
            Log.e("AutoEnhance", "syncDeletes failed", t)
        }

        val nowSec = System.currentTimeMillis() / 1000
        val done = (prefs.getStringSet("done", emptySet()) ?: emptySet()).toMutableSet()

        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} > ?",
            arrayOf("DCIM/Camera%", (nowSec - 600).toString()),
            "${MediaStore.Images.Media._ID} ASC"
        ) ?: return

        val todo = mutableListOf<Pair<Long, String>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "photo.jpg"
                if (!done.contains(id.toString()) && !name.startsWith("ENH_")) {
                    todo.add(id to name)
                }
            }
        }

        for ((id, name) in todo) {
            done.add(id.toString())
            saveDone(done)
            try {
                Thread.sleep(800) // let the camera finish writing
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val outs = Enhancer.enhance(this, uri, name)
                Log.d("AutoEnhance", "Enhanced $name -> $outs")
                if (outs.isNotEmpty()) {
                    val pairs = loadPairs()
                    pairs[id.toString()] = outs.joinToString(";") { it.toString() }
                    savePairs(pairs)
                }
            } catch (t: Throwable) {
                Log.e("AutoEnhance", "Failed on $name", t)
            }
        }
    }

    // ---------- delete the enhanced copy when the original is deleted ----------

    private fun hasFullMediaAccess(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun syncDeletes() {
        if (!hasFullMediaAccess()) return // safety: never delete if we can't see all photos
        val pairs = loadPairs()
        if (pairs.isEmpty()) return
        val ids = pairs.keys.toList()
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
            null
        ) ?: return
        val existing = HashSet<String>()
        cursor.use { c ->
            while (c.moveToNext()) existing.add(c.getLong(0).toString())
        }
        var changed = false
        for ((id, enhanced) in pairs.toList()) {
            if (id !in existing) {
                for (u in enhanced.split(';')) {
                    if (u.isEmpty()) continue
                    try {
                        contentResolver.delete(Uri.parse(u), null, null)
                    } catch (t: Throwable) {
                        Log.w("AutoEnhance", "Could not delete $u: $t")
                    }
                }
                pairs.remove(id)
                changed = true
            }
        }
        if (changed) savePairs(pairs)
    }

    private fun loadPairs(): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        for (s in prefs.getStringSet("pairs", emptySet()) ?: emptySet()) {
            val i = s.indexOf('|')
            if (i > 0) m[s.substring(0, i)] = s.substring(i + 1)
        }
        return m
    }

    private fun savePairs(m: Map<String, String>) {
        val keep = m.entries.sortedByDescending { it.key.toLongOrNull() ?: 0L }.take(300)
        prefs.edit().putStringSet("pairs", keep.map { it.key + "|" + it.value }.toHashSet()).apply()
    }

    private fun saveDone(done: MutableSet<String>) {
        val trimmed = done.mapNotNull { it.toLongOrNull() }.sortedDescending().take(100)
            .map { it.toString() }.toHashSet()
        prefs.edit().putStringSet("done", trimmed).apply()
    }

    private fun buildNotification(): Notification {
        val channelId = "watcher"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Auto Enhance", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Auto Enhance is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
