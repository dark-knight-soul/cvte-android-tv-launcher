package io.github.darkknightsoul.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Трансляция экрана (Miracast).
 *
 * Сам приём делает системное приложение MediaTek (com.mediatek.androidbox/.BoxActivity):
 * оно работает от имени системы и включает приём Wi-Fi Display, что обычному приложению запрещено.
 * Но выглядит оно как белый тестовый экран — поэтому поверх него мы показываем свой экран ожидания
 * отдельным слоем (не активностью: если перекрыть BoxActivity активностью, она остановит приём).
 * Когда телефон подключился — слой плавно исчезает и видна трансляция.
 *
 * Для слоя нужно разрешение «поверх других окон»:
 * adb shell appops set io.github.darkknightsoul.tvlauncher SYSTEM_ALERT_WINDOW allow
 * Без него просто открывается системный экран.
 */
object Cast {

    private const val MTK_PACKAGE = "com.mediatek.androidbox"
    private const val MTK_ACTIVITY = "com.mediatek.androidbox.BoxActivity"

    private var overlay: View? = null
    private var status: TextView? = null
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var dots = 0
    private var waiting = true

    /** Есть ли на телевизоре приёмник Miracast. */
    fun isAvailable(context: Context) = try {
        context.packageManager.getActivityInfo(android.content.ComponentName(MTK_PACKAGE, MTK_ACTIVITY), 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Открыть приём трансляции и показать поверх него наш экран ожидания. */
    fun start(context: Context): Boolean {
        try {
            context.startActivity(Intent().setClassName(MTK_PACKAGE, MTK_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return false
        }
        if (Settings.canDrawOverlays(context)) {
            appContext = context.applicationContext
            // Небольшая задержка — чтобы слой лёг поверх уже открывшегося системного экрана
            main.postDelayed({ show(waitingText = true) }, 300)
            listen()
        }
        return true
    }

    /** Убрать всё (вызывается, когда главный экран снова на виду). */
    fun stop() {
        main.removeCallbacksAndMessages(null)
        removeOverlay()
        receiver?.let { r -> try { appContext?.unregisterReceiver(r) } catch (e: Exception) { } }
        receiver = null
        appContext = null
    }

    private fun show(waitingText: Boolean) {
        val ctx = appContext ?: return
        waiting = waitingText
        if (overlay == null) {
            val view = buildView(ctx)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                // Без фокуса и касаний: кнопки пульта уходят системному экрану (Назад — выход)
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE,
            )
            try {
                (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(view, params)
                overlay = view
            } catch (e: Exception) {
                return
            }
        }
        overlay?.animate()?.cancel()
        overlay?.alpha = 1f
        tickDots()
    }

    /** Плавно убрать слой — открыть трансляцию. */
    private fun reveal() {
        val v = overlay ?: return
        waiting = false
        main.removeCallbacks(dotsRunnable)
        status?.text = "Подключено — запускаю трансляцию"
        v.animate().alpha(0f).setStartDelay(1500).setDuration(600).withEndAction { removeOverlay() }.start()
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        status = null
        try {
            (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
            // Уже удалён
        }
    }

    /** Следим за подключением телефона по Wi-Fi Direct. */
    private fun listen() {
        val ctx = appContext ?: return
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (info?.isConnected == true) reveal()
                        // Телефон отключился, а системный экран ещё открыт — снова показываем ожидание
                        else if (overlay == null && !waiting) show(waitingText = true)
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> updateName(i)
                    else -> reveal() // собственные события MediaTek об успешном подключении
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction("android.net.wifi.p2p.CONNECT_SUCCESS_ACTION")
            addAction("android.net.wifi.p2p.AS_GO_CONNECT_SUCCESS_ACTION")
            addAction("android.net.wifi.p2p.AS_GC_CONNECT_SUCCESS_ACTION")
        }
        ctx.registerReceiver(r, filter)
        receiver = r
    }

    private var nameView: TextView? = null

    /** Фактическое имя телевизора в Wi-Fi Direct — именно его видит телефон. */
    private var deviceName: String? = null

    private fun updateName(i: Intent) {
        @Suppress("DEPRECATION")
        val device = i.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        device?.deviceName?.takeIf { it.isNotBlank() }?.let {
            deviceName = it
            nameView?.text = it
        }
    }

    private val dotsRunnable = object : Runnable {
        override fun run() {
            dots = (dots + 1) % 4
            status?.text = "Ожидание подключения" + ".".repeat(dots)
            main.postDelayed(this, 600)
        }
    }

    private fun tickDots() {
        main.removeCallbacks(dotsRunnable)
        main.post(dotsRunnable)
    }

    private fun buildView(ctx: Context): View {
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 20, 60), Color.rgb(10, 14, 26), Color.rgb(6, 40, 52)))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        // Значок трансляции в круге
        column.addView(FrameLayout(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(40, 255, 255, 255)) }
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_cast) },
                FrameLayout.LayoutParams(ctx.dp(64), ctx.dp(64), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ctx.dp(128), ctx.dp(128)))

        column.addView(TextView(ctx).apply {
            style(40f, Color.WHITE, "sans-serif-medium"); text = "Трансляция экрана"
        }, wrap(ctx, top = 28))
        column.addView(TextView(ctx).apply { style(20f, white(0.7f)); text = "Выберите на телефоне или компьютере этот телевизор:" },
            wrap(ctx, top = 14))
        nameView = TextView(ctx).apply {
            style(34f, Color.rgb(255, 205, 130), "sans-serif-medium")
            // Имя из Wi-Fi Direct (приходит сразу при подписке на события); настройка — только запасной вариант
            text = deviceName ?: Settings.Global.getString(ctx.contentResolver, "wifi_p2p_device_name") ?: "Этот телевизор"
        }
        column.addView(nameView, wrap(ctx, top = 10))

        // Подсказки, где найти трансляцию на разных устройствах
        val tips = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(36), ctx.dp(22), ctx.dp(36), ctx.dp(22))
            background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = ctx.dp(20).toFloat() }
        }
        listOf(
            "📱  Android — шторка уведомлений → «Трансляция», «Smart View» или «Беспроводной дисплей»",
            "💻  Windows — нажмите Win + K и выберите телевизор",
            "🍎  iPhone и Mac — нужен AirPlay: этот режим его не поддерживает",
        ).forEachIndexed { idx, t ->
            tips.addView(TextView(ctx).apply { style(19f, white(0.9f)); text = t }, wrap(ctx, top = if (idx == 0) 0 else 12))
        }
        column.addView(tips, wrap(ctx, top = 40))

        status = TextView(ctx).apply { style(22f, Color.WHITE); text = "Ожидание подключения" }
        column.addView(status, wrap(ctx, top = 40))
        column.addView(TextView(ctx).apply { style(15f, white(0.45f)); text = "Кнопка «Назад» — выход" }, wrap(ctx, top = 14))

        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun wrap(ctx: Context, top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = ctx.dp(top); gravity = Gravity.CENTER_HORIZONTAL }
}
