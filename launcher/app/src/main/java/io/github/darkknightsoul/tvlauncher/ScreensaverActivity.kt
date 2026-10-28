package io.github.darkknightsoul.tvlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Заставка: обои выбранной категории сменяют друг друга с медленным наездом камеры,
 * круглые часы, дата и погода. Любая кнопка пульта — выход.
 */
class ScreensaverActivity : Activity() {

    private val slideMs = 40_000L
    private val fadeMs = 1_800L

    /** Шаг «наезда камеры»: сдвиг за шаг меньше пикселя, на глаз движение плавное. */
    private val kenBurnsStepMs = 80L

    private lateinit var root: FrameLayout
    private lateinit var images: Array<ImageView>
    private var front = 0
    private lateinit var info: LinearLayout
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var weatherLine: TextView
    private lateinit var credit: TextView

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var pending: Wallpaper? = null
    private var loading = false
    private var stopped = false

    /** Когда включилась заставка — для автоотключения телевизора. */
    private var startedAt = 0L

    /** Таймер сна уже поставлен — при выходе из заставки его нужно отменить. */
    @Volatile
    private var powerOffArmed = false
    private lateinit var notice: TextView

    // Параметры текущего «наезда»
    private var slideStart = 0L
    private var dx = 0f
    private var dy = 0f

    private val nextSlide = object : Runnable {
        override fun run() {
            val wp = pending
            if (wp != null) {
                pending = null
                show(wp)
                // Следующее фото начинаем готовить за 10 секунд до смены — так в памяти не больше двух фото
                main.postDelayed(preloadTask, slideMs - 10_000)
                main.postDelayed(this, slideMs)
            } else {
                // Следующее фото ещё грузится — проверим чуть позже
                preload()
                main.postDelayed(this, 2_000)
            }
        }
    }

    private val preloadTask = Runnable { preload() }

    /** Медленное увеличение и сдвиг текущего фото — вручную, редкими шагами. */
    private val kenBurns = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - slideStart) / (slideMs + 2f * fadeMs)).coerceAtMost(1f)
            val v = images[front]
            v.scaleX = 1f + 0.12f * t
            v.scaleY = v.scaleX
            v.translationX = dx * t
            v.translationY = dy * t
            if (t < 1f) main.postDelayed(this, kenBurnsStepMs)
        }
    }

    private val tickText = object : Runnable {
        override fun run() {
            updateTexts()
            checkAutoOff()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    /** Ночь: с 23:00 до 6:00, если ночной режим включён. */
    private fun isNight(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return prefs.nightMode && (h >= 23 || h < 6)
    }

    /**
     * Заставка идёт дольше заданного — ставим таймер сна телевизора на 1 минуту и предупреждаем.
     * Любая кнопка закрывает заставку и отменяет таймер.
     */
    private fun checkAutoOff() {
        val hours = prefs.autoOffHours
        if (hours <= 0 || powerOffArmed) return
        if (SystemClock.uptimeMillis() - startedAt < hours * 3_600_000L) return
        // Под заставку играет музыка — не выключаем
        if ((getSystemService(AUDIO_SERVICE) as android.media.AudioManager).isMusicActive) return
        powerOffArmed = true
        CvteTv.connect(this) {
            // Пока подключались к прошивке, могли уже выйти из заставки — тогда ничего не ставим
            if (!powerOffArmed) return@connect
            tv.safe {
                if (!powerOffArmed) return@safe
                val minutes = CvteTv.powerOffIn(1)
                // Вышли из заставки, пока ставили таймер, — сразу отменяем
                if (!powerOffArmed) { CvteTv.setSleepMinutes(0); return@safe }
                main.post {
                    notice.text = if (minutes != null) "Телевизор выключится через $minutes мин — нажмите любую кнопку, чтобы отменить"
                    else "Не удалось включить таймер выключения"
                    notice.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        setContentView(buildLayout())
        window.setBackgroundDrawable(null)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        startedAt = SystemClock.uptimeMillis()
        // Ночью часы и текст тоже приглушены
        val dim = if (isNight()) 0.55f else 1f
        info.alpha = dim
        credit.alpha = dim
        updateTexts()
        main.post(tickText)
        showFirstFromCache()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        main.removeCallbacksAndMessages(null)
        if (powerOffArmed) {
            // Вышли из заставки (нажали кнопку) — отменяем выключение телевизора
            powerOffArmed = false
            tv.safe { CvteTv.setSleepMinutes(0) }
        }
        pending?.bitmap?.recycle()
        pending = null
        // Заставка ушла с экрана — закрываем и освобождаем картинки
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        images.forEach { it.animate().cancel(); it.setImageDrawable(null) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        finish()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        finish()
        return true
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun buildLayout(): View {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        images = Array(2) {
            ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        }
        images.forEach { root.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        // Затемнение под часы запекается прямо в фото (см. bakeScreensaverShade) — отдельного слоя нет

        time = TextView(this).apply { style(56f, Color.WHITE, "sans-serif-medium"); softShadow() }
        date = TextView(this).apply { style(24f, Color.WHITE, "sans-serif-medium"); softShadow() }
        weatherLine = TextView(this).apply { style(20f, white(0.9f)); softShadow() }

        info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(AnalogClockView(this@ScreensaverActivity), LinearLayout.LayoutParams(dp(250), dp(250)))
            addView(LinearLayout(this@ScreensaverActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(36), 0, 0, 0)
                addView(time)
                addView(date, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
                addView(weatherLine, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(12) })
            })
        }
        info.translationZ = 2f
        root.addView(info, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(72), 0, 0, dp(64))
        })

        credit = TextView(this).apply { style(12f, white(0.45f)); translationZ = 2f }
        root.addView(credit, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(40), dp(28))
        })

        // Предупреждение об автоотключении телевизора
        notice = TextView(this).apply {
            style(20f, Color.WHITE, "sans-serif-medium")
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(190, 0, 0, 0)); cornerRadius = dp(24).toFloat()
            }
            visibility = View.GONE
            translationZ = 2f
        }
        root.addView(notice, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(48)
        })
        return root
    }

    private fun updateTexts() {
        val now = Date()
        val ru = Locale("ru")
        time.text = SimpleDateFormat("HH:mm", ru).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", ru).format(now).replaceFirstChar { it.uppercase() }
        val moon = moonPhase()
        val w = Weather(this).cached()
        weatherLine.text = if (w != null) "${w.emoji}  ${w.tempText} · ${w.description}   ${moon.emoji}" else moon.emoji + " " + moon.name
    }

    /** Первое фото — сразу из уже скачанных, без ожидания сети. */
    private fun showFirstFromCache() {
        val dm = resources.displayMetrics
        val night = isNight()
        loading = true
        io.safe {
            val wp = WallpaperStore.get(this).nextCached(dm.widthPixels, dm.heightPixels)
            wp?.let { bakeScreensaverShade(it.bitmap, night) }
            main.post {
                loading = false
                if (stopped) { wp?.bitmap?.recycle(); return@post }
                if (wp != null) pending = wp
                // Если в кэше пусто — nextSlide сам дождётся загрузки из интернета
                main.post(nextSlide)
            }
        }
    }

    /** Загружаем следующее фото в фоне (из интернета или кэша). */
    private fun preload() {
        if (loading || pending != null) return
        loading = true
        val night = isNight()
        // Ночью — тёмные фото космоса
        val category = if (night) Category.SPACE else prefs.category
        val dm = resources.displayMetrics
        wallNet.safe {
            val wp = WallpaperStore.get(this).next(category, dm.widthPixels, dm.heightPixels)
            wp?.let { bakeScreensaverShade(it.bitmap, night) }
            main.post {
                loading = false
                if (stopped) wp?.bitmap?.recycle() else if (wp != null) pending = wp
            }
        }
    }

    /** Плавная смена фото + медленный «наезд камеры» (эффект Кена Бёрнса). */
    private fun show(wp: Wallpaper) {
        val incoming = images[1 - front]
        val outgoing = images[front]
        front = 1 - front

        incoming.setImageBitmap(wp.bitmap)
        incoming.scaleX = 1f; incoming.scaleY = 1f
        incoming.translationX = 0f; incoming.translationY = 0f
        // Новое фото проявляется поверх старого (текст ещё выше — у него Z = 2)
        incoming.translationZ = 1f
        outgoing.translationZ = 0f

        // Сдвиг меньше запаса от увеличения (12 %), чтобы края фото не показывались
        val shift = dp(40).toFloat()
        dx = Random.nextFloat() * 2 * shift - shift
        dy = Random.nextFloat() * shift - shift / 2
        slideStart = SystemClock.uptimeMillis()
        main.removeCallbacks(kenBurns)
        main.post(kenBurns)

        ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f).setDuration(fadeMs).apply {
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    outgoing.alpha = 0f
                    outgoing.setImageDrawable(null)
                    // Фото закрывает весь экран — чёрный фон под ним больше не нужно рисовать
                    root.background = null
                }
            })
        }.start()

        credit.text = "Фото: ${wp.credit}"
        // Немного сдвигаем часы при каждой смене — чтобы картинка на экране не «застаивалась»
        val d = dp(18).toFloat()
        info.animate().translationX(Random.nextFloat() * 2 * d - d).translationY(Random.nextFloat() * 2 * d - d)
            .setDuration(fadeMs).start()
    }
}
