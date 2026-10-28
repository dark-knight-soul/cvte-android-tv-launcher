package io.github.darkknightsoul.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

/** Белый цвет с заданной прозрачностью (0..1). */
fun white(alpha: Float): Int = Color.argb((alpha * 255).roundToInt(), 255, 255, 255)

fun TextView.style(sizeSp: Float, color: Int, family: String = "sans-serif", bold: Boolean = false) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
}

/** Мягкая тень под текстом — чтобы он читался на любых обоях. */
fun TextView.softShadow() = setShadowLayer(context.dp(6).toFloat(), 0f, context.dp(1).toFloat(), Color.argb(140, 0, 0, 0))

/** Отключает обрезку дочерних элементов, чтобы увеличенная плитка не обрезалась краями ряда. */
fun <T : ViewGroup> T.noClip(): T = apply {
    clipChildren = false
    clipToPadding = false
}

/**
 * Основа всех фокусируемых элементов: скруглённые углы, белая рамка и плавное увеличение при фокусе.
 * Теней (elevation) нет намеренно — на слабом процессоре ТВ они сильно тормозят отрисовку.
 */
open class Card(context: Context, private val radius: Float) : FrameLayout(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = context.dp(3).toFloat()
    }

    /** Насколько увеличивать при фокусе. */
    var focusScale = 1.1f

    /** Действие по кнопке Menu на пульте (или долгому нажатию OK). */
    var onMenu: (() -> Unit)? = null

    init {
        // Обрезку по скруглённому контуру (clipToOutline) не используем: на Mali-450 она
        // стоит дорого на каждом кадре. Углы уже скруглены в самих картинках и фонах.
        isFocusable = true
        isClickable = true
        // Без фона ViewGroup пропускает свой draw() — а в нём рисуется рамка фокуса
        setWillNotDraw(false)
        setOnLongClickListener { onMenu?.invoke(); onMenu != null }
    }

    /** Фон — диагональный градиент из двух цветов. */
    fun setGradient(from: Int, to: Int) {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            cornerRadius = radius
        }
    }

    /** Фон — сплошной цвет (дешевле градиента для видеочипа). */
    fun setSolid(color: Int) {
        background = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) focusScale else 1f
        // Только перерисовка: смена foreground вызывала бы пересчёт раскладки всего экрана на каждое нажатие
        invalidate()
        // Без withLayer: переключение во временную текстуру на этом видеочипе дороже самой анимации
        animate().scaleX(scale).scaleY(scale).setDuration(150)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    /** Белая рамка — только у элемента в фокусе. */
    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        if (isFocused) {
            val half = ringPaint.strokeWidth / 2
            canvas.drawRoundRect(half, half, width - half, height - half, radius, radius, ringPaint)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && onMenu != null) {
            onMenu?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

/** Плитка приложения: баннер во всю плитку или иконка с подписью. */
class Tile(context: Context) : Card(context, context.dp(12).toFloat()) {

    private val banner = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val title = TextView(context).apply {
        style(13f, Color.WHITE, "sans-serif-medium")
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }

    private val column: LinearLayout

    init {
        addView(banner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
            addView(icon, LinearLayout.LayoutParams(context.dp(40), context.dp(40)))
            addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8)
            })
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Режим баннера: готовая картинка размером с плитку, без подписи. */
    fun showBanner(bmp: Bitmap) {
        background = null
        banner.setImageBitmap(bmp)
        banner.visibility = VISIBLE
        // Колонку с иконкой прячем целиком — чтобы её не измерять и не раскладывать
        column.visibility = GONE
    }

    /** Режим иконки: значок по центру и подпись под ним. */
    fun showIcon(d: Drawable, text: String) {
        banner.visibility = GONE
        column.visibility = VISIBLE
        icon.setImageDrawable(d)
        icon.visibility = VISIBLE
        title.text = text
        title.visibility = VISIBLE
    }
}

/** Компактная «таблетка» источника: значок, название и состояние (подключено / нет). */
class SourceChip(context: Context) : Card(context, context.dp(14).toFloat()) {

    val name = TextView(context).apply { style(15f, Color.WHITE, "sans-serif-medium") }
    val status = TextView(context).apply { style(11f, white(0.6f)) }
    val icon = ImageView(context).apply { setImageResource(R.drawable.ic_hdmi) }

    init {
        focusScale = 1.08f
        setSolid(Color.argb(150, 20, 24, 40))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(14), 0, context.dp(10), 0)
            addView(icon, LinearLayout.LayoutParams(context.dp(24), context.dp(24)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(12), 0, 0, 0)
                addView(name)
                addView(status, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(4)
                })
            })
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

/** Круглая кнопка со значком (оформление, настройки) в правом верхнем углу. */
class RoundButton(context: Context, iconRes: Int, size: Int) : Card(context, size / 2f) {
    private val image = ImageView(context).apply { setImageResource(iconRes) }

    init {
        focusScale = 1.15f
        setSolid(white(0.14f))
        addView(image, LayoutParams(size / 2, size / 2, Gravity.CENTER))
    }

    fun setIcon(iconRes: Int) = image.setImageResource(iconRes)
}

/**
 * Запасной фон, пока нет обоев: тёмно-синий с мягкими цветными «сияниями».
 * Рисуется один раз в картинку — дальше это обычная текстура, без пересчёта градиентов на каждом кадре.
 */
fun renderAurora(w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.rgb(10, 14, 26))
    val glows = listOf(
        floatArrayOf(0.08f, 0.0f, 0.75f) to Color.argb(110, 123, 77, 255),
        floatArrayOf(0.95f, 0.15f, 0.55f) to Color.argb(80, 42, 140, 255),
        floatArrayOf(0.75f, 1.05f, 0.70f) to Color.argb(70, 0, 170, 160),
    )
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for ((g, color) in glows) {
        paint.shader = RadialGradient(g[0] * w, g[1] * h, g[2] * w, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }
    return bmp
}
