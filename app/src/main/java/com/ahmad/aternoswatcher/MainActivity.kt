package com.ahmad.aternoswatcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val bg = Color.rgb(7, 12, 24)
    private val card = Color.rgb(17, 27, 46)
    private val muted = Color.rgb(151, 167, 191)
    private val green = Color.rgb(52, 211, 153)
    private val red = Color.rgb(248, 113, 113)
    private val amber = Color.rgb(251, 191, 36)
    private lateinit var statusSummary: TextView
    private lateinit var checkedAt: TextView
    private lateinit var serverCards: LinearLayout
    private lateinit var checkButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            if (::serverCards.isInitialized) refreshScreen()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleMonitoring()
        requestNotificationPermission()
        buildScreen()
        refreshScreen()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(25), dp(20), dp(30))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)

        val brand = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val brandText = column().apply {
            addView(text("Aternos Watcher", 23f, Color.WHITE, true))
            addView(text("مراقبة مباشرة لسيرفرات Minecraft", 12f, muted))
        }
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        content.addView(brand)

        val hero = column().apply {
            background = rounded(card, 22)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        hero.addView(text("حالة المراقبة", 17f, Color.WHITE, true))
        statusSummary = text("جارٍ تجهيز الفحص…", 16f, amber, true)
        hero.addView(statusSummary, marginTop(5))
        checkedAt = text("آخر فحص مكتمل: لم يتم الفحص بعد", 12f, muted)
        hero.addView(checkedAt, marginTop(3))
        content.addView(hero, marginTop(22))

        val section = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("السيرفرات", 19f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("3 عناوين محفوظة", 11f, muted))
        }
        content.addView(section, marginTop(24))
        serverCards = column()
        content.addView(serverCards, marginTop(10))

        checkButton = Button(this).apply {
            text = "⟳   فحص الآن"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(5, 150, 105), 16)
            setOnClickListener {
                ServerCheckWorker.enqueueOneTime(this@MainActivity)
                refreshScreen()
                Toast.makeText(this@MainActivity, "بدأ فحص Minecraft المباشر…", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(checkButton, marginTop(20).apply { height = dp(54) })

        val testNotificationButton = Button(this).apply {
            text = "♬   اختبار الإشعار"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(43, 57, 78), 15)
            setOnClickListener { sendTestNotification() }
        }
        content.addView(testNotificationButton, marginTop(9).apply { height = dp(48) })

        val info = column().apply {
            background = rounded(Color.rgb(25, 37, 58), 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text("كيف يعمل الفحص؟", 14f, Color.WHITE, true))
            addView(text("يتصل التطبيق ببروتوكول حالة Minecraft مباشرة، ويقرأ الاستجابة وعدد اللاعبين والإصدار عند توفرها. لا يعتمد على نتيجة محفوظة من موقع خارجي.", 12f, muted))
            addView(text("هذا فحص اتصال فقط، وليس دخول لاعب أو بوت إلى السيرفر. لا يرسل بيانات تسجيل دخول ولا يحاول إبقاء Aternos قيد التشغيل.", 12f, amber))
            addView(text("تعمل المراقبة الدورية كل 15 دقيقة تقريباً. قد يؤخر أندرويد التنفيذ بسبب البطارية أو الشبكة.", 11f, muted))
        }
        content.addView(info, marginTop(18))
        content.addView(
            text("الحالة ONLINE تعني أن السيرفر ردّ على بروتوكول Minecraft وقت الفحص. UNKNOWN تعني أن النتيجة غير حاسمة وليست OFFLINE.", 10f, muted)
                .apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER },
            marginTop(14)
        )
        setContentView(scroll)
    }

    private fun refreshScreen() {
        val prefs = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE)
        var onlineCount = 0
        var offlineCount = 0
        var unknownCount = 0
        serverCards.removeAllViews()

        ServerConfig.servers.forEachIndexed { index, server ->
            val storedState = prefs.getString("state_$index", null)
            val state = storedState ?: when {
                !prefs.contains("online_$index") -> "WAITING"
                prefs.getBoolean("online_$index", false) -> "ONLINE"
                else -> "OFFLINE"
            }
            when (state) {
                "ONLINE" -> onlineCount++
                "OFFLINE" -> offlineCount++
                else -> unknownCount++
            }

            val color = when (state) {
                "ONLINE" -> green
                "OFFLINE" -> red
                else -> amber
            }
            val label = when (state) {
                "ONLINE" -> "ONLINE"
                "OFFLINE" -> "OFFLINE"
                "UNKNOWN" -> "غير مؤكّد"
                else -> "بانتظار الفحص"
            }
            val checked = prefs.getLong("checked_at_$index", 0L)
            val isStale = checked > 0L && System.currentTimeMillis() - checked > TimeUnit.MINUTES.toMillis(30)
            val item = column().apply {
                background = rounded(card, 18)
                setPadding(dp(15), dp(13), dp(15), dp(13))
            }
            val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("●", 19f, color, true))
            val details = column().apply {
                addView(text(server.name, 15f, Color.WHITE, true))
                addView(text(server.address, 11f, muted))
            }
            top.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val badgeText = if (isStale) "$label • قديمة" else label
            val badge = text(badgeText, 10f, color, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = rounded(Color.rgb(31, 45, 65), 20)
            }
            top.addView(badge)
            item.addView(top)

            val playersOnline = if (prefs.contains("players_online_$index")) prefs.getInt("players_online_$index", 0) else null
            val playersMax = if (prefs.contains("players_max_$index")) prefs.getInt("players_max_$index", 0) else null
            val version = prefs.getString("version_$index", null)
            if (state == "ONLINE" && (playersOnline != null || version != null)) {
                val facts = buildList {
                    if (playersOnline != null && playersMax != null) add("اللاعبون: $playersOnline/$playersMax")
                    if (!version.isNullOrBlank()) add("الإصدار: $version")
                }.joinToString("   •   ")
                item.addView(text(facts, 11f, green), marginTop(5))
            }
            val error = prefs.getString("error_$index", "").orEmpty()
            if (state == "UNKNOWN" && error.isNotBlank()) {
                item.addView(text(error, 11f, amber), marginTop(4))
            }
            val timeText = if (checked == 0L) "لم يُفحص بعد" else
                "آخر محاولة: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(checked))
            item.addView(text(timeText, 10f, muted), marginTop(3))
            serverCards.addView(item, marginBottom(10))
        }

        val checking = prefs.getBoolean("is_checking", false)
        checkButton.text = if (checking) "⟳   جارٍ فحص السيرفرات…" else "⟳   فحص الآن"
        checkButton.isEnabled = !checking
        checkButton.alpha = if (checking) 0.7f else 1f

        statusSummary.text = if (checking) {
            "جارٍ فحص السيرفرات الآن"
        } else {
            "متصل: $onlineCount   •   غير متصل: $offlineCount   •   غير مؤكد: $unknownCount"
        }
        statusSummary.setTextColor(if (checking) amber else if (unknownCount > 0) amber else green)

        val last = prefs.getLong("last_checked", 0L)
        checkedAt.text = if (last == 0L) "آخر فحص مكتمل: لم يتم الفحص بعد" else
            "آخر فحص مكتمل: " + SimpleDateFormat("HH:mm  •  dd/MM", Locale.getDefault()).format(Date(last))
        val globalError = prefs.getString("last_check_error", "").orEmpty()
        if (globalError.isNotBlank() && !checking) {
            checkedAt.text = checkedAt.text.toString() + "  •  $globalError"
        }
    }

    private fun sendTestNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "اسمح للتطبيق بالإشعارات من إعدادات أندرويد أولاً", Toast.LENGTH_LONG).show()
            requestNotificationPermission()
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "server_online_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "تنبيهات تشغيل السيرفرات",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "تنبيهات حالة سيرفرات Minecraft"
                enableVibration(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            }
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("Aternos Watcher")
            .setContentText("الإشعارات تعمل بشكل صحيح على هذا الجهاز.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        manager.notify(999, notification)
        Toast.makeText(this, "تم إرسال إشعار تجريبي", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleMonitoring() {
        val request = PeriodicWorkRequestBuilder<ServerCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ServerConfig.WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun marginTop(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
}
