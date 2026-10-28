package io.github.darkknightsoul.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val columns = 7
    private val gap by lazy { dp(18) }
    private val side by lazy { dp(64) }

    private val bgRotateMs = 20 * 60_000L
    private val weatherRefreshMs = 30 * 60_000L

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private val backgrounds = arrayOfNulls<ImageView>(2)
    private var bgFront = 0

    private lateinit var clock: TextView
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var date: TextView
    private lateinit var dayInfo: TextView
    private lateinit var netIcon: ImageView
    private lateinit var netLabel: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherRange: TextView
    private lateinit var appsGrid: LinearLayout
    private lateinit var sourcesRow: LinearLayout

    private val tvManager by lazy { getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager? }

    /** Источники из прошивки CVTE (ТВ, HDMI, AV…). Пусто — пока сервис не ответил. */
    private var tvSources: List<TvSource> = emptyList()

    /** Плашки HDMI по id входа Android (…/HW4, HW5, HW6) — для состояния «подключено». */
    private val hdmiChips = LinkedHashMap<String, SourceChip>()

    /** Какой источник CVTE соответствует входу Android (HDMI 1 ↔ первый HDMI в списке CVTE и т.д.). */
    private val hdmiSourceIds = HashMap<String, Int>()

    /** Последние известные состояния HDMI — чтобы ловить именно момент подключения. */
    private val hdmiStates = HashMap<String, Int>()

    /** Для какого «последнего приложения» строилась сетка — чтобы перестроить её только при смене. */
    private var builtForLastApp: String? = null
    private val main = Handler(Looper.getMainLooper())

    // Размер плитки: 7 штук в ряд по ширине экрана, пропорции 16:9
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val tileW by lazy { (screenW - side * 2 - gap * (columns - 1)) / columns }
    private val tileH by lazy { tileW * 9 / 16 }

    private val bgFile by lazy { File(filesDir, "home_bg.jpg") }

    /** Часы обновляются раз в минуту по системному сигналу. */
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }

    /**
     * Установка/удаление приложений — перестраиваем сетку (с паузой: события приходят пачками).
     * Пока лаунчер в фоне, ничего не делаем, только запоминаем: иначе он перерисовывает плитки
     * прямо во время запуска другого приложения (сервисы Google шлют такие события постоянно).
     */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!started) { appsDirty = true; return }
            main.removeCallbacks(reloadAfterPackages)
            main.postDelayed(reloadAfterPackages, 700)
        }
    }
    private var started = false
    private var appsDirty = false

    /** Фон отдан системе при нехватке памяти — при возврате загрузить заново. */
    private var bgDropped = false
    private val reloadAfterPackages = Runnable { reloadApps(keepFocus = true) }

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateNetwork()
            // Появилась сеть — можно подтянуть погоду и обои
            if (isOnline()) refreshWeather(force = false)
        }
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            val before = hdmiStates[inputId]
            updateHdmiState(inputId)
            val now = SystemClock.uptimeMillis()
            if (state != TvInputManager.INPUT_STATE_CONNECTED) hdmiLostAt[inputId] = now
            // Устройство только что подключили/включили, а мы на главном экране — сразу переходим на него.
            // Защита от «дребезга»: сигнал должен был пропадать хотя бы на 2 с, и не чаще раза в 30 с
            if (state == TvInputManager.INPUT_STATE_CONNECTED && before != null && before != state &&
                prefs.autoHdmi && resumed &&
                now - (hdmiLostAt[inputId] ?: 0L) >= 2_000 && now - lastAutoSwitch >= 30_000) {
                lastAutoSwitch = now
                hdmiSourceIds[inputId]?.let { id ->
                    val name = tvSources.firstOrNull { it.id == id }?.name ?: "HDMI"
                    Toast.makeText(this@MainActivity, "$name: устройство подключено — переключаю", Toast.LENGTH_SHORT).show()
                    openSource(id)
                }
            }
        }
        override fun onInputAdded(inputId: String) = buildSources()
        override fun onInputRemoved(inputId: String) = buildSources()
    }

    private var resumed = false
    private val hdmiLostAt = HashMap<String, Long>()
    private var lastAutoSwitch = 0L

    /** Последний загруженный список приложений — чтобы переставлять плитки без перечитывания баннеров. */
    private var apps: List<AppEntry> = emptyList()

    /** Последняя погода — чтобы не разбирать JSON из настроек каждую минуту. */
    private var weatherNow: WeatherInfo? = null
    private val clockFormat = SimpleDateFormat("HH:mm", Locale("ru"))
    private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    private val rotateBackground = Runnable { refreshBackground(force = true) }
    private val weatherTimer = object : Runnable {
        override fun run() {
            refreshWeather(force = true)
            main.postDelayed(this, weatherRefreshMs)
        }
    }

    /** Заставка после бездействия на главном экране. */
    private val idleTimer = Runnable { startScreensaver() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildLayout())
        buildSources()
        reloadApps(keepFocus = false)
        loadSavedBackground()
        // Источники берём у прошивки CVTE — там есть и антенное ТВ, которого нет в стандартном списке Android
        CvteTv.connect(this) {
            tv.safe {
                val list = CvteTv.sources()
                main.post { tvSources = list; buildSources() }
            }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        @Suppress("DEPRECATION")
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        updateClock()
        updateNetwork()
        tvManager?.registerCallback(inputCallback, main)
        hdmiChips.keys.forEach { updateHdmiState(it) }
        nowPlaying.start()
        started = true
        if (appsDirty) {
            // Пока нас не было, что-то установили/удалили — перечитываем список
            appsDirty = false
            reloadApps(keepFocus = false)
        } else if (prefs.lastApp != builtForLastApp && apps.isNotEmpty()) {
            // Вернулись из приложения — просто ставим его первым, без перечитывания баннеров (мгновенно)
            builtForLastApp = prefs.lastApp
            buildApps(sortApps(apps, prefs), keepFocus = false)
        }
        // Если фон отдавали системе из-за нехватки памяти — подгружаем его обратно с диска
        if (bgDropped) { bgDropped = false; loadSavedBackground(force = true) }

        showWeather(Weather(this).cached())
        refreshWeather(force = false)
        main.postDelayed(weatherTimer, weatherRefreshMs)

        // Обои меняем, если с прошлой смены прошло больше 20 минут — но не сразу,
        // а через 5 с, чтобы скачивание не мешало плавному возврату на главный экран
        val sinceChange = System.currentTimeMillis() - prefs.bgChangedAt
        main.postDelayed(rotateBackground, maxOf(5_000L, bgRotateMs - sinceChange))
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(timeReceiver)
        unregisterReceiver(networkReceiver)
        tvManager?.unregisterCallback(inputCallback)
        nowPlaying.stop()
        started = false
        main.removeCallbacks(weatherTimer)
        main.removeCallbacks(rotateBackground)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resetIdle()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(idleTimer)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
    }

    /**
     * Системе не хватает памяти, а лаунчер не на экране — отдаём картинку фона (8 МБ).
     * При возврате она за доли секунды подгрузится с диска.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!started && level >= TRIM_MEMORY_BACKGROUND && !bgDropped) {
            bgDropped = true
            bgGen++
            backgrounds.forEach { it?.animate()?.cancel(); it?.setImageDrawable(null); it?.alpha = 0f }
            // Пока фон грузится обратно, окно будет тёмно-синим, а не чёрным
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(10, 14, 26)))
        }
    }

    /** Любая кнопка пульта сбрасывает таймер заставки. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetIdle()
        return super.dispatchKeyEvent(event)
    }

    /** Нажали Home, уже находясь на главном экране, — возвращаемся к началу. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scroll.smoothScrollTo(0, 0)
        firstAppTile()?.requestFocus()
    }

    @Deprecated("Back на главном экране ничего не делает")
    override fun onBackPressed() {
        // Это главный экран — уходить некуда
    }

    // ---------- Разметка ----------

    private fun buildLayout(): View {
        root = FrameLayout(this)
        for (i in 0..1) {
            backgrounds[i] = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
            root.addView(backgrounds[i], FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, dp(26), side, dp(24))
        }.noClip()

        content.addView(buildHeader())
        // Распорка: прижимает плитки к низу, чтобы посередине были видны обои
        content.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { height = 0 })
        content.getChildAt(1).minimumHeight = dp(20)

        content.addView(sectionTitle("Приложения"))
        appsGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.noClip()
        content.addView(appsGrid)

        content.addView(sectionTitle("Источники"))
        sourcesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.noClip()
        content.addView(sourcesRow)

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }.noClip()
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        // Плавного появления содержимого нет намеренно: прозрачность у целого блока
        // заставляет видеочип рисовать весь экран через промежуточный слой
        return root
    }

    /** Шапка: слева большие часы и дата, справа — статус-бар, кнопки и погода. */
    private fun buildHeader(): View {
        clock = TextView(this).apply { style(100f, Color.WHITE, "sans-serif-medium"); softShadow() }
        date = TextView(this).apply { style(26f, Color.WHITE, "sans-serif-medium"); softShadow() }
        dayInfo = TextView(this).apply { style(16f, white(0.8f)); softShadow() }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(dayInfo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            // Весь левый блок (часы, дата, закат и луна) рисуется в собственную текстуру и перерисовывается
            // только раз в минуту; на каждом кадре видеочип получает одну команду вместо десятка
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        // Статус-бар: сеть + кнопки «Оформление» и «Настройки»
        netIcon = ImageView(this)
        netLabel = TextView(this).apply { style(14f, white(0.85f)); softShadow() }
        val btnSize = dp(46)
        val themeBtn = RoundButton(this, R.drawable.ic_palette, btnSize).apply { setOnClickListener { appearanceMenu() } }
        val settingsBtn = RoundButton(this, R.drawable.ic_settings, btnSize).apply { setOnClickListener { openSettings() } }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(netIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(netLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8); rightMargin = dp(22) })
            addView(themeBtn, LinearLayout.LayoutParams(btnSize, btnSize))
            addView(settingsBtn, LinearLayout.LayoutParams(btnSize, btnSize).apply { leftMargin = dp(14) })
        }.noClip()

        weatherIcon = TextView(this).apply { style(40f, Color.WHITE) }
        weatherTemp = TextView(this).apply { style(56f, Color.WHITE, "sans-serif"); softShadow() }
        nowPlaying = NowPlayingView(this)
        weatherDesc = TextView(this).apply { style(17f, white(0.9f)); softShadow(); gravity = Gravity.END }
        weatherRange = TextView(this).apply { style(14f, white(0.7f)); softShadow(); gravity = Gravity.END }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(status)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(weatherIcon)
                addView(weatherTemp, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(weatherDesc, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(weatherRange, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(nowPlaying, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }.noClip()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }.noClip()
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        style(16f, white(0.7f), "sans-serif-medium")
        this.text = text
        letterSpacing = 0.04f
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(14)
            bottomMargin = dp(10)
        }
    }

    private fun tileParams(index: Int, h: Int = tileH) = LinearLayout.LayoutParams(tileW, h).apply {
        if (index > 0) leftMargin = gap
    }

    // ---------- Часы, сеть, погода ----------

    private fun updateClock() {
        val now = Date()
        clock.text = clockFormat.format(now)
        date.text = dateFormat.format(now).replaceFirstChar { it.uppercase() }
        updateDayInfo(weatherNow)
    }

    /** Строка под датой: закат и фаза луны. */
    private fun updateDayInfo(w: WeatherInfo?) {
        val moon = moonPhase()
        val sunset = w?.let { "🌇  Закат ${it.sunset}     " }.orEmpty()
        dayInfo.text = "$sunset${moon.emoji}  ${moon.name} ${moon.percent}%"
    }

    @Suppress("DEPRECATION")
    private fun isOnline(): Boolean =
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true

    @Suppress("DEPRECATION")
    private fun updateNetwork() {
        val info = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo
        when {
            info == null || !info.isConnected -> {
                netIcon.setImageResource(R.drawable.ic_wifi); netIcon.alpha = 0.35f; netLabel.text = "Нет сети"
            }
            info.type == ConnectivityManager.TYPE_ETHERNET -> {
                netIcon.setImageResource(R.drawable.ic_ethernet); netIcon.alpha = 1f; netLabel.text = "Кабель"
            }
            else -> {
                netIcon.setImageResource(R.drawable.ic_wifi); netIcon.alpha = 1f; netLabel.text = "Wi-Fi"
            }
        }
    }

    private var weatherLoading = false

    private fun refreshWeather(force: Boolean) {
        val weather = Weather(this)
        if (weatherLoading || (!force && !weather.stale())) return
        weatherLoading = true
        net.safe {
            val w = weather.refresh()
            main.post {
                weatherLoading = false
                if (w != null) showWeather(w)
            }
        }
    }

    private fun showWeather(w: WeatherInfo?) {
        weatherNow = w
        if (w == null) {
            weatherIcon.text = ""; weatherTemp.text = ""; weatherDesc.text = ""; weatherRange.text = ""
            return
        }
        weatherIcon.text = w.emoji
        weatherTemp.text = w.tempText
        weatherDesc.text = "${w.city} · ${w.description}"
        weatherRange.text = "днём ${signed(w.max)}   ночью ${signed(w.min)}"
        updateDayInfo(w)
    }

    // ---------- Обои ----------

    /** Сразу при запуске показываем прошлые обои с диска — без ожидания интернета. */
    private fun loadSavedBackground(force: Boolean = false) {
        // Отдельный поток от списка приложений — фон и плитки грузятся параллельно
        wallNet.safe {
            val bmp = if (bgFile.exists()) BitmapFactory.decodeFile(bgFile.path) else null
            val shown = bmp ?: renderAurora(screenW, screenH)
            // Если свежие обои успели прийти раньше — старые с диска уже не нужны
            main.post { if (force || bgGen == 0) showBackground(shown) }
        }
    }

    private fun refreshBackground(force: Boolean) {
        main.removeCallbacks(rotateBackground)
        main.postDelayed(rotateBackground, bgRotateMs)
        if (!force) return
        val category = prefs.category
        wallNet.safe {
            val wp = WallpaperStore.get(this).next(category, screenW, screenH) ?: return@safe
            val composed = composeHomeBackground(wp.bitmap, screenW, screenH)
            wp.bitmap.recycle()
            try {
                bgFile.outputStream().use { composed.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } catch (e: Exception) {
                // Не сохранилось — не страшно, при следующем запуске будет запасной фон
            }
            main.post {
                prefs.bgChangedAt = System.currentTimeMillis()
                // Лаунчер сейчас не на экране — не держим 8 МБ в памяти: файл уже на диске, покажем при возврате
                if (!started) { bgDropped = true; return@post }
                showBackground(composed)
            }
        }
    }

    /** Номер последней смены фона — чтобы старая анимация не стёрла новую картинку. */
    private var bgGen = 0

    /** Плавная смена фона: новая картинка проявляется поверх старой. */
    private fun showBackground(bmp: Bitmap) {
        val gen = ++bgGen
        val incoming = backgrounds[1 - bgFront]!!
        val outgoing = backgrounds[bgFront]!!
        bgFront = 1 - bgFront
        // cancel() выполняет withEndAction прошлой анимации — там стоит проверка номера
        incoming.animate().cancel()
        outgoing.animate().cancel()
        outgoing.alpha = 1f
        // Новая картинка — над старой, но под содержимым
        root.removeView(incoming)
        root.addView(incoming, 1, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        // Без withLayer: у ImageView без фона прозрачность считается прямо при отрисовке, слой не нужен
        incoming.animate().alpha(1f).setDuration(1200).withEndAction {
            if (gen != bgGen) return@withEndAction
            outgoing.setImageDrawable(null)
            outgoing.alpha = 0f
            // Окно больше не нужно закрашивать — его полностью закрывает фон
            window.setBackgroundDrawable(null)
        }.start()
    }

    // ---------- Заставка и оформление ----------

    private fun resetIdle() {
        main.removeCallbacks(idleTimer)
        val minutes = prefs.idleMinutes
        if (minutes > 0) main.postDelayed(idleTimer, minutes * 60_000L)
    }

    private fun startScreensaver() {
        startActivity(Intent(this, ScreensaverActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun appearanceMenu() {
        val idleText = prefs.idleMinutes.let { if (it == 0) "выключена" else "через $it мин" }
        val offText = prefs.autoOffHours.let { if (it == 0) "никогда" else "через $it ч заставки" }
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "Обои: ${prefs.category.title}" to { chooseCategory() },
            "Заставка: $idleText" to { chooseIdle() },
            "Ночной режим заставки (23:00–6:00): ${onOff(prefs.nightMode)}" to { prefs.nightMode = !prefs.nightMode },
            "Выключать телевизор: $offText" to { chooseAutoOff() },
            "Сразу открывать HDMI при подключении: ${onOff(prefs.autoHdmi)}" to { prefs.autoHdmi = !prefs.autoHdmi },
            "Показать заставку сейчас" to { startScreensaver() },
            "Сменить обои сейчас" to { refreshBackground(force = true) },
        )
        val hidden = prefs.hidden.size
        if (hidden > 0) actions += "Показать скрытые приложения ($hidden)" to { prefs.unhideAll(); reloadApps(keepFocus = false) }
        dialog("Оформление", actions.map { it.first }) { which -> actions[which].second() }
    }

    private fun onOff(v: Boolean) = if (v) "вкл" else "выкл"

    private fun chooseAutoOff() {
        val options = listOf(1, 2, 3, 4, 6, 0)
        dialog("Выключать телевизор", options.map {
            (if (it == prefs.autoOffHours) "✓  " else "     ") + if (it == 0) "Никогда" else "Через $it ч непрерывной заставки"
        }) { which -> prefs.autoOffHours = options[which] }
    }

    private fun chooseCategory() {
        val all = Category.values()
        dialog("Обои", all.map { (if (it == prefs.category) "✓  " else "     ") + it.title }) { which ->
            prefs.category = all[which]
            refreshBackground(force = true)
        }
    }

    private fun chooseIdle() {
        val options = listOf(2, 5, 10, 15, 30, 0)
        dialog("Заставка", options.map {
            (if (it == prefs.idleMinutes) "✓  " else "     ") + if (it == 0) "Выключена" else "Через $it мин"
        }) { which ->
            prefs.idleMinutes = options[which]
            resetIdle()
        }
    }

    private fun dialog(title: String, items: List<String>, onPick: (Int) -> Unit) {
        // Пока открыт диалог, нажатия идут в его окно — сбрасываем таймер заставки сами,
        // иначе заставка включится поверх открытого меню
        AlertDialog.Builder(this, R.style.Theme_Home_Dialog)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setOnKeyListener { _, _, _ -> resetIdle(); false }
            .setOnDismissListener { resetIdle() }
            .show()
    }

    // ---------- Источники: HDMI ----------

    /**
     * Ряд источников: ТВ (антенна), HDMI 1–3, AV — по списку из прошивки CVTE.
     * Состояние HDMI («подключено») берём у стандартного TvInputManager: CVTE его не различает.
     */
    private fun buildSources() {
        // Если фокус был на плашке источника — вернём его на то же место после перестройки
        val focusedIdx = currentFocus?.let { sourcesRow.indexOfChild(it) } ?: -1
        sourcesRow.removeAllViews()
        hdmiChips.clear()
        hdmiSourceIds.clear()
        val androidHdmi = tvManager?.tvInputList.orEmpty()
            .filter { it.type == TvInputInfo.TYPE_HDMI }
            .sortedBy { it.id }
            .map { it.id }
        // Редкие входы (YPbPr, VGA) не показываем — на этом телевизоре ими не пользуются.
        // Пока прошивка не ответила — временно показываем HDMI по данным Android
        val shown = tvSources.filter { it.isTv || it.isHdmi || it.category == "INPUTSOURCE_AV" }
            .ifEmpty { androidHdmi.mapIndexed { i, _ -> TvSource(-1 - i, "INPUTSOURCE_HDMI", "HDMI ${i + 1}", "") } }
        val cvteHdmi = shown.filter { it.isHdmi }.sortedBy { it.id }

        for (src in shown) {
            val chip = SourceChip(this).apply {
                name.text = when {
                    src.isTv -> "ТВ"
                    src.category == "INPUTSOURCE_AV" -> "AV"
                    else -> src.name
                }
                icon.setImageResource(when {
                    src.isTv -> R.drawable.ic_tv
                    src.isHdmi -> R.drawable.ic_hdmi
                    else -> R.drawable.ic_input
                })
                status.text = when {
                    src.isTv -> "Антенна"
                    src.isHdmi -> ""
                    else -> "Видеовход"
                }
                setOnClickListener { openSource(src.id) }
            }
            sourcesRow.addView(chip, tileParams(sourcesRow.childCount, dp(56)))
            if (src.isHdmi) {
                androidHdmi.getOrNull(cvteHdmi.indexOf(src))?.let { inputId ->
                    hdmiChips[inputId] = chip
                    hdmiSourceIds[inputId] = src.id
                    updateHdmiState(inputId)
                }
            }
        }
        if (focusedIdx >= 0 && sourcesRow.childCount > 0) {
            sourcesRow.getChildAt(minOf(focusedIdx, sourcesRow.childCount - 1)).requestFocus()
        }
    }

    /** Переключить источник через прошивку CVTE и открыть её родной полноэкранный ТВ-экран. */
    private fun openSource(id: Int) {
        tv.safe {
            // id < 0 — временная плашка без данных CVTE: переключить не можем, просто открываем ТВ-экран
            if (id >= 0) CvteTv.setSource(id)
            main.post {
                // ТВ-экран уже открыт (второе нажатие/событие, пока переключались) — второй раз не открываем
                if (!resumed) return@post
                if (!CvteTv.openTvScreen(this)) {
                    Toast.makeText(this, "Не удалось открыть ТВ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateHdmiState(inputId: String) {
        val chip = hdmiChips[inputId] ?: return
        val state = try {
            tvManager?.getInputState(inputId)
        } catch (e: Exception) {
            null
        }
        val connected = state == TvInputManager.INPUT_STATE_CONNECTED
        state?.let { hdmiStates[inputId] = it }
        chip.status.text = when (state) {
            TvInputManager.INPUT_STATE_CONNECTED -> "● Подключено"
            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "● Ожидание"
            else -> "Не подключено"
        }
        chip.status.setTextColor(if (connected) Color.rgb(120, 255, 170) else white(0.55f))
    }

    private fun openSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_SETTINGS),
            Intent().setComponent(ComponentName("com.cvte.tv.androidsetting", "com.cvte.tv.androidsetting.GlobalSettingActivity")),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Пробуем следующий вариант
            }
        }
        Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
    }

    // ---------- Приложения ----------

    /** Загружаем список в фоне (иконки читаются с диска), строим сетку в основном потоке. */
    private fun reloadApps(keepFocus: Boolean) {
        // Запоминаем сразу, а не после загрузки: иначе onStart, пришедший раньше io, запустит вторую загрузку
        builtForLastApp = prefs.lastApp
        io.safe {
            val list = loadApps(this, prefs, tileW, tileH, dp(12).toFloat())
            main.post {
                apps = list
                buildApps(list, keepFocus)
            }
        }
    }

    private fun buildApps(apps: List<AppEntry>, keepFocus: Boolean) {
        val focusedPkg = if (keepFocus) currentFocus?.tag as? String else null
        appsGrid.removeAllViews()
        var row: LinearLayout? = null
        var toFocus: View? = null
        apps.forEachIndexed { i, app ->
            if (i % columns == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.noClip()
                appsGrid.addView(row, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    if (i > 0) topMargin = gap
                })
            }
            val tile = Tile(this).apply {
                tag = app.pkg
                if (app.banner != null) {
                    showBanner(app.banner)
                } else {
                    setGradient(tone(app.color, 0.55f), tone(app.color, 0.25f))
                    showIcon(app.icon ?: getDrawable(R.drawable.ic_launcher)!!, app.label)
                }
                setOnClickListener { launch(app) }
                onMenu = { appMenu(app) }
            }
            row!!.addView(tile, tileParams(i % columns))
            if (app.pkg == focusedPkg) toFocus = tile
        }
        (toFocus ?: if (currentFocus == null || !keepFocus) firstAppTile() else null)?.requestFocus()
    }

    private fun firstAppTile(): View? = (appsGrid.getChildAt(0) as? LinearLayout)?.getChildAt(0)

    private fun launch(app: AppEntry) {
        try {
            startActivity(app.launch)
            prefs.lastApp = app.pkg
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть «${app.label}»", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appMenu(app: AppEntry) {
        val actions = mutableListOf("Поставить первым", "Скрыть с главного экрана")
        if (!app.isSystem) actions += "Удалить приложение"
        dialog(app.label, actions) { which ->
            when (actions[which]) {
                "Поставить первым" -> {
                    prefs.moveFirst(app.pkg)
                    reloadApps(keepFocus = true)
                }
                "Скрыть с главного экрана" -> {
                    prefs.hide(app.pkg)
                    reloadApps(keepFocus = false)
                    Toast.makeText(this, "Вернуть: кнопка «Оформление» вверху справа", Toast.LENGTH_LONG).show()
                }
                "Удалить приложение" -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            }
        }
    }
}
