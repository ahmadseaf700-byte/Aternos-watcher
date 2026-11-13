package com.ahmad.aternoswatcher

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val bg = Color.rgb(9, 15, 28)
    private val card = Color.rgb(19, 29, 48)
    private val muted = Color.rgb(153, 169, 194)
    private val green = Color.rgb(52, 211, 153)
    private val red = Color.rgb(248, 113, 113)
    private lateinit var statusSummary: TextView
    private lateinit var checkedAt: TextView
    private lateinit var serverCards: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleMonitoring()
        requestNotificationPermission()
        buildScreen()
        refreshScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::serverCards.isInitialized) refreshScreen()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(content)

        val brand = row()
        val logo = text("AW", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(16, 185, 129), 16)
        }
        brand.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brandText = column().apply {
            addView(text("Aternos Watcher", 23f, Color.WHITE, true))
            addView(text("مراقبة سيرفراتك… بدون ما تضل فاتح التطبيق", 12f, muted))
        }
        brand.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(13) })
        content.addView(brand)

        val hero = column().apply {
            background = rounded(card, 22)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        hero.addView(text("لوحة المراقبة", 18f, Color.WHITE, true))
        statusSummary = text("جارٍ تجهيز المراقبة…", 14f, green, true)
        hero.addView(statusSummary)
        checkedAt = text("آخر فحص: لم يتم الفحص بعد", 12f, muted)
        hero.addView(checkedAt)
        content.addView(hero, marginTop(22))

        val section = row().apply {
            addView(text("سيرفراتك", 19f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("3 سيرفرات", 12f, muted))
        }
        content.addView(section, marginTop(22))
        serverCards = column()
        content.addView(serverCards, marginTop(10))

        val checkButton = Button(this).apply {
            text = "⟳   فحص الآن"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(5, 150, 105), 16)
            setOnClickListener {
                ServerCheckWorker.enqueueOneTime(this@MainActivity)
                Toast.makeText(this@MainActivity, "بدأ فحص السيرفرات…", Toast.LENGTH_SHORT).show()
            }
        }
        content.addView(checkButton, marginTop(20).apply { height = dp(54) })

        val info = column().apply {
            background = rounded(Color.rgb(25, 37, 58), 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(text("كيف تعمل التنبيهات؟", 14f, Color.WHITE, true))
            addView(text("يراقب التطبيق السيرفرات تلقائياً كل 15 دقيقة تقريباً، ويرسل إشعاراً بصوت واهتزاز عند انتقال أي سيرفر من Offline إلى Online.", 12f, muted))
            addView(text("ملاحظة: أندرويد قد يؤخر الفحص بسبب توفير البطارية. أول فحص يسجل الحالة فقط، ولا يرسل تنبيهاً فورياً.", 11f, Color.rgb(250, 204, 120)))
        }
        content.addView(info, marginTop(18))
        content.addView(text("المصدر: خدمة حالة Minecraft العامة mcsrvstat.us • قد تختلف النتيجة عن حالة لوحة Aternos.", 10f, muted).apply { gravity = Gravity.CENTER }, marginTop(16))
        setContentView(scroll)
    }

    private fun refreshScreen() {
        val prefs = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE)
        var onlineCount = 0
        var offlineCount = 0
        serverCards.removeAllViews()
        ServerConfig.servers.forEachIndexed { index, server ->
            val key = "online_$index"
            val known = prefs.contains(key)
            val online = prefs.getBoolean(key, false)
            if (known && online) onlineCount++ else if (known) offlineCount++
            val state = when { !known -> "بانتظار أول فحص"; online -> "ONLINE"; else -> "OFFLINE" }
            val color = when { !known -> muted; online -> green; else -> red }
            val item = row().apply {
                background = rounded(card, 18)
                setPadding(dp(16), dp(15), dp(16), dp(15))
                gravity = Gravity.CENTER_VERTICAL
            }
            item.addView(text(if (known) "●" else "○", 22f, color, true))
            val details = column().apply {
                addView(text(server.name, 15f, Color.WHITE, true))
                addView(text(server.address, 11f, muted))
            }
            item.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            val badge = text(state, 10f, color, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(7), dp(9), dp(7))
                background = rounded(Color.rgb(31, 45, 65), 20)
            }
            item.addView(badge)
            serverCards.addView(item, marginBottom(10))
        }
        val unknown = ServerConfig.servers.size - onlineCount - offlineCount
        statusSummary.text = if (unknown == 3) "المراقبة مفعّلة • بانتظار أول نتيجة" else "متصل: $onlineCount    غير متصل: $offlineCount"
        val last = prefs.getLong("last_checked", 0L)
        checkedAt.text = if (last == 0L) "آخر فحص: لم يتم الفحص بعد" else
            "آخر فحص: " + SimpleDateFormat("HH:mm  •  dd/MM", Locale.getDefault()).format(Date(last))
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
        ) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
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
            setPadding(0, dp(4), 0, dp(4))
        }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun marginTop(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun marginBottom(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(value) }
}
