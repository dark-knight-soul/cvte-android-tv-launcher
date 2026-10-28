package io.github.darkknightsoul.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.io.File
import java.text.Collator
import java.util.Locale

/** Приложение на главном экране. */
class AppEntry(
    val pkg: String,
    val label: String,
    val launch: Intent,
    /** Баннер, заранее уменьшенный до размера плитки и со скруглёнными углами. */
    val banner: Bitmap?,
    /** Иконка — только для приложений без баннера. */
    val icon: Drawable?,
    /** Основной цвет иконки — из него делается фон плитки. */
    val color: Int,
    val isSystem: Boolean,
)

/** Служебные пакеты, которые никогда не показываем на главном экране. */
private val EXCLUDED = setOf(
    "com.cvte.tv.launcher.fall",
    "com.cvte.fac.menu",
    "cn.cvte.tv.factory.autotest",
    "com.example.user.myapplication",
    "com.mediatek.androidbox",
    "com.mediatek.wwtv.setupwizard",
    "com.android.shell",
    // Дубль плитки «Настройки» в ряду источников
    "com.cvte.tv.androidsetting",
    // Бесполезные на телевизоре плитки
    "com.android.documentsui",
    "com.google.android.voicesearch",
)

/** Порядок по умолчанию: сначала кино и ТВ, в конце служебные приложения. */
private val DEFAULT_RANK = listOf(
    "com.google.android.youtube.tv",
    "ru.kinopoisk.tv",
    "com.netflix.mediaclient",
    "uz.turontelecom.cinerama.iptv",
    "uz.telecom.telecomtv",
    "ag.kinom",
    "com.setanta.app",
    "com.vk.tv",
    "com.spotify.tv.android",
)
private val UTILITIES = listOf(
    "com.lonelycatgames.Xplore",
    "com.cvte.tv.media",
    "com.android.vending",
    "com.mar.btdelay",
    "com.roysolberg.android.developertools",
)

private fun defaultRank(pkg: String): Int {
    DEFAULT_RANK.indexOf(pkg).let { if (it >= 0) return it }
    UTILITIES.indexOf(pkg).let { if (it >= 0) return 2000 + it }
    return 1000
}

/**
 * Собирает список приложений: сначала ТВ-ярлыки (с баннерами), потом обычные.
 * Баннеры сразу готовятся под размер плитки [tileW]×[tileH] со скруглением [radius].
 */
fun loadApps(context: Context, prefs: Prefs, tileW: Int, tileH: Int, radius: Float): List<AppEntry> {
    val pm = context.packageManager
    val found = LinkedHashMap<String, AppEntry>()
    val hidden = prefs.hidden
    val tilesDir = File(context.cacheDir, "tiles").apply { mkdirs() }
    val usedTiles = HashSet<String>()
    for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        for (ri in pm.queryIntentActivities(query, 0)) {
            val ai = ri.activityInfo
            val pkg = ai.packageName
            if (pkg in found || pkg in EXCLUDED || pkg == context.packageName || pkg in hidden) continue
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setClassName(pkg, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            // Один «битый» баннер или иконка не должны ломать весь список
            val key = "${pkg}_${File(ai.applicationInfo.sourceDir).lastModified()}_${tileW}x$tileH"
            usedTiles += key
            val banner = try {
                cachedBanner(tilesDir, key) {
                    (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.let { renderRounded(it, tileW, tileH, radius) }
                }
            } catch (e: Exception) {
                null
            }
            // Иконку и её цвет считаем только когда баннера нет — меньше работы и памяти
            val icon = if (banner == null) ai.loadIcon(pm) else null
            found[pkg] = AppEntry(
                pkg = pkg,
                label = ri.loadLabel(pm).toString().trim(),
                launch = launch,
                banner = banner,
                icon = icon,
                color = icon?.let { dominantColor(it) } ?: 0,
                isSystem = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }
    }
    cleanTileCache(tilesDir, usedTiles)
    return sortApps(found.values.toList(), prefs)
}

/** Порядок: последнее открытое, закреплённые пользователем, порядок по умолчанию, по алфавиту. */
fun sortApps(list: List<AppEntry>, prefs: Prefs): List<AppEntry> {
    val collator = Collator.getInstance(Locale("ru"))
    val order = prefs.order
    val last = prefs.lastApp
    return list.sortedWith(
        compareBy<AppEntry> { if (it.pkg == last) 0 else 1 }
            .thenBy { order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { defaultRank(it.pkg) }
            .thenComparator { a, b -> collator.compare(a.label, b.label) }
    )
}

/**
 * Готовая плитка-баннер из кэша на диске или, если её нет, — отрисовка из APK и сохранение.
 * Ключ — пакет + время изменения его APK + размер плитки: обновилось приложение — плитка перерисуется.
 * Из кэша PNG ~220×125 читается за 2–3 мс вместо открытия ресурсов чужого APK (десятки мс).
 * Пустой файл-метка означает «у приложения нет баннера».
 */
private fun cachedBanner(dir: File, key: String, render: () -> Bitmap?): Bitmap? {
    val f = File(dir, "$key.png")
    if (f.exists()) return if (f.length() == 0L) null else BitmapFactory.decodeFile(f.path)
    val bmp = render()
    try {
        if (bmp == null) f.createNewFile() else f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } catch (e: Exception) {
        // Кэш не записался — не страшно, в следующий раз отрисуем снова
    }
    return bmp
}

/** Удаляем плитки приложений, которых больше нет (или которые обновились). */
private fun cleanTileCache(dir: File, used: Set<String>) {
    dir.listFiles()?.forEach { if (it.name.removeSuffix(".png") !in used) it.delete() }
}

/**
 * Рисует картинку в битмап точного размера плитки со скруглёнными углами.
 * Так видеочипу не нужно на каждом кадре масштабировать баннер и вырезать углы.
 */
fun renderRounded(d: Drawable, w: Int, h: Int, radius: Float): Bitmap {
    val plain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val copy = d.constantState?.newDrawable()?.mutate() ?: d
    val canvas = Canvas(plain)
    val iw = copy.intrinsicWidth
    val ih = copy.intrinsicHeight
    if (iw > 0 && ih > 0) {
        // Рисуем в родном размере и масштабируем холст: некоторые баннеры (с gravity в XML)
        // при setBounds не растягиваются, а обрезаются
        copy.setBounds(0, 0, iw, ih)
        canvas.scale(w.toFloat() / iw, h.toFloat() / ih)
    } else {
        copy.setBounds(0, 0, w, h)
    }
    copy.draw(canvas)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(plain, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    Canvas(out).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, paint)
    plain.recycle()
    return out
}

/**
 * Определяет «главный» цвет иконки: уменьшаем её до 16×16 и усредняем
 * насыщенные пиксели (серые и прозрачные почти не учитываем).
 */
fun dominantColor(d: Drawable): Int {
    val size = 16
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val copy = d.constantState?.newDrawable()?.mutate() ?: d
    copy.setBounds(0, 0, size, size)
    copy.draw(Canvas(bmp))
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    val hsv = FloatArray(3)
    for (x in 0 until size) for (y in 0 until size) {
        val p = bmp.getPixel(x, y)
        if (Color.alpha(p) < 180) continue
        Color.colorToHSV(p, hsv)
        val w = 0.05f + hsv[1] * hsv[2]
        r += Color.red(p) * w; g += Color.green(p) * w; b += Color.blue(p) * w; total += w
    }
    bmp.recycle()
    if (total == 0f) return Color.rgb(52, 64, 100)
    return Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
}

/** Цвет с заданной насыщенностью и яркостью — для приятного тёмного фона плитки. */
fun tone(color: Int, value: Float): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[1] = hsv[1].coerceIn(0.25f, 0.8f)
    hsv[2] = value
    return Color.HSVToColor(hsv)
}

/** Настройки лаунчера: порядок и скрытые приложения, обои, заставка. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    var category: Category
        get() = Category.byName(sp.getString("category", null))
        set(v) = sp.edit().putString("category", v.name).apply()

    /** Последнее открытое приложение — оно идёт первым («Продолжить просмотр»). */
    var lastApp: String?
        get() = sp.getString("last_app", null)
        set(v) = sp.edit().putString("last_app", v).apply()

    /** Сразу открывать HDMI-вход, когда к нему подключили и включили устройство. */
    var autoHdmi: Boolean
        get() = sp.getBoolean("auto_hdmi", true)
        set(v) = sp.edit().putBoolean("auto_hdmi", v).apply()

    /** Через сколько часов непрерывной заставки выключать телевизор (0 — никогда). */
    var autoOffHours: Int
        get() = sp.getInt("auto_off", 3)
        set(v) = sp.edit().putInt("auto_off", v).apply()

    /** Ночной режим заставки (с 23:00 до 6:00 — космос и приглушённая яркость). */
    var nightMode: Boolean
        get() = sp.getBoolean("night", true)
        set(v) = sp.edit().putBoolean("night", v).apply()

    /** Когда последний раз менялись обои на главном экране. */
    var bgChangedAt: Long
        get() = sp.getLong("bg_changed", 0)
        set(v) = sp.edit().putLong("bg_changed", v).apply()

    /** Через сколько минут бездействия на главном экране включать заставку (0 — никогда). */
    var idleMinutes: Int
        get() = sp.getInt("idle", 5)
        set(v) = sp.edit().putInt("idle", v).apply()

    val hidden: Set<String> get() = sp.getStringSet("hidden", emptySet())!!.toSet()

    val order: List<String> get() = sp.getString("order", "")!!.split('\n').filter { it.isNotBlank() }

    fun hide(pkg: String) = sp.edit().putStringSet("hidden", hidden + pkg).apply()

    fun unhideAll() = sp.edit().remove("hidden").apply()

    /** Ставит приложение первым в списке. */
    fun moveFirst(pkg: String) {
        val list = order.toMutableList().apply { remove(pkg); add(0, pkg) }
        sp.edit().putString("order", list.joinToString("\n")).apply()
    }
}
