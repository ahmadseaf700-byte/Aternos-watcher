package com.ahmad.aternoswatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ServerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(ServerConfig.PREFS, Context.MODE_PRIVATE)
        try {
            for ((index, server) in ServerConfig.servers.withIndex()) {
                val online = checkOnline(server.address) ?: return@withContext Result.retry()
                val key = "online_$index"
                val hadPrevious = prefs.contains(key)
                val wasOnline = prefs.getBoolean(key, false)
                if (hadPrevious && !wasOnline && online) notifyOnline(index, server.name, server.address)
                prefs.edit().putBoolean(key, online).apply()
            }
            prefs.edit().putLong("last_checked", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun checkOnline(address: String): Boolean? {
        val connection = (URL("https://api.mcsrvstat.us/3/$address").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AternosWatcher/1.0")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optBoolean("online", false)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun notifyOnline(index: Int, name: String, address: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "server_online"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تنبيهات تشغيل السيرفرات", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعار عند انتقال سيرفر من Offline إلى Online"
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("السيرفر أصبح Online!")
            .setContentText("$name جاهز للدخول — $address")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name أصبح متصلاً الآن. افتح Minecraft وانضم إلى السيرفر."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        manager.notify(200 + index, notification)
    }

    companion object {
        fun enqueueOneTime(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ServerCheckWorker>().build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }
}
