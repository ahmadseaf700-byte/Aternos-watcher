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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class ServerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(ServerConfig.PREFS, Context.MODE_PRIVATE)
        try {
            for ((index, server) in ServerConfig.servers.withIndex()) {
                val online = checkMinecraftStatus(server.address) ?: return@withContext Result.retry()
                val key = "online_$index"
                val hadPrevious = prefs.contains(key)
                val wasOnline = prefs.getBoolean(key, false)
                if (hadPrevious && !wasOnline && online) notifyOnline(index, server.name, server.address)
                prefs.edit().putBoolean(key, online).apply()
            }
            prefs.edit()
                .putLong("last_checked", System.currentTimeMillis())
                .putString("last_check_error", "")
                .apply()
            Result.success()
        } catch (_: Exception) {
            prefs.edit().putString("last_check_error", "تعذّر إكمال الفحص").apply()
            Result.retry()
        }
    }

    /**
     * Uses Minecraft's own Server List Ping protocol.
     * This performs a status handshake only: it does NOT log in as a player.
     * Returns null for an inconclusive/network failure so we never report it as OFFLINE.
     */
    private fun checkMinecraftStatus(address: String): Boolean? {
        val parts = address.split(":", limit = 2)
        if (parts.size != 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 7000)
            socket.soTimeout = 7000

            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val handshake = ByteArrayOutputStream()
            val handshakeOut = DataOutputStream(handshake)
            writeVarInt(handshakeOut, 0)       // Handshake packet ID
            writeVarInt(handshakeOut, -1)      // Protocol version: status ping accepts any
            writeString(handshakeOut, host)
            handshakeOut.writeShort(port)
            writeVarInt(handshakeOut, 1)       // Next state: status
            writePacket(output, handshake.toByteArray())

            val statusRequest = ByteArrayOutputStream()
            val statusOut = DataOutputStream(statusRequest)
            writeVarInt(statusOut, 0)          // Status Request packet ID
            writePacket(output, statusRequest.toByteArray())

            val packetLength = readVarInt(input)
            if (packetLength <= 0 || packetLength > 1_048_576) return null
            val packetBytes = ByteArray(packetLength)
            input.readFully(packetBytes)
            val packetInput = DataInputStream(ByteArrayInputStream(packetBytes))
            if (readVarInt(packetInput) != 0) return null
            val jsonLength = readVarInt(packetInput)
            if (jsonLength <= 0 || jsonLength > packetInput.available()) return null
            val jsonBytes = ByteArray(jsonLength)
            packetInput.readFully(jsonBytes)
            val status = JSONObject(String(jsonBytes, Charsets.UTF_8))

            // A valid status response means the Minecraft server answered the protocol ping.
            status.has("version") && status.has("players")
        } catch (_: ConnectException) {
            false
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun writePacket(output: DataOutputStream, payload: ByteArray) {
        writeVarInt(output, payload.size)
        output.write(payload)
        output.flush()
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(output, bytes.size)
        output.write(bytes)
    }

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if ((remaining and 0x7F.inv()) == 0) {
                output.writeByte(remaining)
                return
            }
            output.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var position = 0
        while (position < 35) {
            val current = input.readUnsignedByte()
            result = result or ((current and 0x7F) shl position)
            if ((current and 0x80) == 0) return result
            position += 7
        }
        throw IOException("VarInt is too big")
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
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name أجاب على فحص Minecraft وأصبح متاحاً. افتح Minecraft وانضم إلى السيرفر."))
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
