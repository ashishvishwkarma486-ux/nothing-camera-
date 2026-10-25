package com.nogy.autoenhance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
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
                val out = Enhancer.enhance(this, uri, name)
                Log.d("AutoEnhance", "Enhanced $name -> $out")
            } catch (t: Throwable) {
                Log.e("AutoEnhance", "Failed on $name", t)
            }
        }
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
