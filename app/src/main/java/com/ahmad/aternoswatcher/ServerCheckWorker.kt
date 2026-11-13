package com.ahmad.aternoswatcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
import java.net.UnknownHostException

private data class ProbeResult(
    val state: String,
    val online: Boolean?,
    val playersOnline: Int? = null,
    val playersMax: Int? = null,
    val version: String? = null,
    val error: String? = null
)

class ServerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(ServerConfig.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_checking", true).apply()
        var hasUnknown = false
        try {
            for ((index, server) in ServerConfig.servers.withIndex()) {
                val result = checkMinecraftStatus(server.address)
                val now = System.currentTimeMillis()
                val edit = prefs.edit()
                    .putString("state_$index", result.state)
                    .putLong("checked_at_$index", now)
                    .putString("error_$index", result.error.orEmpty())

                result.playersOnline?.let { edit.putInt("players_online_$index", it) }
                result.playersMax?.let { edit.putInt("players_max_$index", it) }
                result.version?.let { edit.putString("version_$index", it) }
                if (result.state == "UNKNOWN") hasUnknown = true

                if (result.online != null) {
                    val key = "online_$index"
                    val hadPrevious = prefs.contains(key)
                    val wasOnline = prefs.getBoolean(key, false)
                    if (hadPrevious && !wasOnline && result.online) {
                        notifyOnline(index, server.name, server.address, result.playersOnline, result.playersMax)
                    }
                    edit.putBoolean(key, result.online)
                }
                edit.apply()
            }
            prefs.edit()
                .putLong("last_checked", System.currentTimeMillis())
                .putString("last_check_error", if (hasUnknown) "بعض النتائج غير مؤكدة؛ تحقق من تفاصيل كل سيرفر" else "")
                .apply()
            Result.success()
        } catch (_: Exception) {
            prefs.edit().putString("last_check_error", "حدث خطأ أثناء تنفيذ الفحص").apply()
            Result.retry()
        } finally {
            prefs.edit().putBoolean("is_checking", false).apply()
        }
    }

    /**
     * Minecraft Server List Ping (status handshake only).
     * It does not authenticate, join as a player, or keep a server awake.
     */
    private fun checkMinecraftStatus(address: String): ProbeResult {
        val parts = address.split(":", limit = 2)
        if (parts.size != 2) return ProbeResult("UNKNOWN", null, error = "عنوان السيرفر غير صالح")
        val host = parts[0]
        val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return ProbeResult("UNKNOWN", null, error = "المنفذ غير صالح")

        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), 6500)
            socket.soTimeout = 6500
            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val handshake = ByteArrayOutputStream()
            val handshakeOut = DataOutputStream(handshake)
            writeVarInt(handshakeOut, 0)
            writeVarInt(handshakeOut, -1)
            writeString(handshakeOut, host)
            handshakeOut.writeShort(port)
            writeVarInt(handshakeOut, 1)
            writePacket(output, handshake.toByteArray())

            val request = ByteArrayOutputStream()
            writeVarInt(DataOutputStream(request), 0)
            writePacket(output, request.toByteArray())

            val packetLength = readVarInt(input)
            if (packetLength <= 0 || packetLength > 1_048_576) {
                return ProbeResult("UNKNOWN", null, error = "استجابة غير مفهومة من السيرفر")
            }
            val packet = ByteArray(packetLength)
            input.readFully(packet)
            val packetInput = DataInputStream(ByteArrayInputStream(packet))
            if (readVarInt(packetInput) != 0) {
                return ProbeResult("UNKNOWN", null, error = "رد بروتوكول غير متوقع")
            }
            val jsonLength = readVarInt(packetInput)
            if (jsonLength <= 0 || jsonLength > packetInput.available()) {
                return ProbeResult("UNKNOWN", null, error = "بيانات حالة غير مكتملة")
            }
            val json = ByteArray(jsonLength)
            packetInput.readFully(json)
            val status = JSONObject(String(json, Charsets.UTF_8))
            if (!status.has("version") || !status.has("players")) {
                return ProbeResult("UNKNOWN", null, error = "السيرفر لم يُرجع بيانات Minecraft كاملة")
            }

            val players = status.optJSONObject("players")
            val version = status.optJSONObject("version")?.optString("name")?.takeIf { it.isNotBlank() }
            ProbeResult(
                state = "ONLINE",
                online = true,
                playersOnline = players?.takeIf { it.has("online") }?.optInt("online"),
                playersMax = players?.takeIf { it.has("max") }?.optInt("max"),
                version = version
            )
        } catch (_: ConnectException) {
            ProbeResult("OFFLINE", false, error = "رفض السيرفر الاتصال على هذا المنفذ")
        } catch (_: SocketTimeoutException) {
            ProbeResult("UNKNOWN", null, error = "انتهت مهلة الرد؛ لا يمكن الجزم بالحالة")
        } catch (_: UnknownHostException) {
            ProbeResult("UNKNOWN", null, error = "تعذّر العثور على عنوان السيرفر")
        } catch (_: IOException) {
            ProbeResult("UNKNOWN", null, error = "تعذّر قراءة رد Minecraft")
        } catch (_: Exception) {
            ProbeResult("UNKNOWN", null, error = "رد غير صالح من السيرفر")
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

    private fun notifyOnline(index: Int, name: String, address: String, players: Int?, maxPlayers: Int?) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "server_online_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "تنبيهات تشغيل السيرفرات", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "إشعار عند انتقال سيرفر من OFFLINE إلى ONLINE"
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
            manager.createNotificationChannel(channel)
        }
        val playerText = if (players != null && maxPlayers != null) " • اللاعبون: $players/$maxPlayers" else ""
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("السيرفر أصبح متاحاً")
            .setContentText("$name • ONLINE$playerText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name أجاب على فحص Minecraft وأصبح متاحاً. العنوان: $address"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        manager.notify(200 + index, notification)
    }

    companion object {
        fun enqueueOneTime(context: Context) {
            context.getSharedPreferences(ServerConfig.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("is_checking", true).apply()
            val request = OneTimeWorkRequestBuilder<ServerCheckWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "manual_server_check", ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
