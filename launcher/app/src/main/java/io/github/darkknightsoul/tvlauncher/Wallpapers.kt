package io.github.darkknightsoul.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/** Категория обоев: название и разделы «избранных фото» Wikimedia Commons. */
enum class Category(val title: String, val sources: List<String>) {
    NATURE("Пейзажи и горы", listOf("Featured pictures of landscapes", "Featured pictures of mountains")),
    SUNSETS("Закаты", listOf("Featured pictures of sunsets")),
    CITIES("Города", listOf("Featured pictures of cityscapes")),
    SPACE("Космос", listOf("Featured pictures of astronomy", "Featured pictures of galaxies", "Featured pictures of the Moon")),
    MIX("Микс", NATURE.sources + SUNSETS.sources + CITIES.sources + SPACE.sources);

    companion object {
        fun byName(name: String?) = values().firstOrNull { it.name == name } ?: NATURE
    }
}

/** Один общий экземпляр [Wallpapers] на всё приложение (главный экран и заставка). */
object WallpaperStore {
    private var instance: Wallpapers? = null

    @Synchronized
    fun get(context: Context): Wallpapers =
        instance ?: Wallpapers(context.applicationContext).also { instance = it }
}

/** Фото с подписью автора. */
class Photo(val url: String, val credit: String)

/** Готовая к показу картинка и её подпись. */
class Wallpaper(val bitmap: Bitmap, val credit: String)

/**
 * Обои: список фото по категориям (обновляется раз в 3 дня), сами фото кэшируются на диске.
 * Все методы — блокирующие, вызывать только из фонового потока [io].
 */
class Wallpapers(private val context: Context) {

    private val dir = File(context.cacheDir, "wall").apply { mkdirs() }
    private val maxFiles = 40
    private var lastFile: File? = null

    /** Список фото категории: из кэша, если он свежий, иначе — из интернета. */
    private fun photos(category: Category): List<Photo> {
        val cache = File(context.filesDir, "list_${category.name}.json")
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < 3 * 24 * 3600_000L
        if (fresh) parse(cache.readText()).let { if (it.isNotEmpty()) return it }
        return try {
            val list = category.sources.flatMap { fetch(it) }.shuffled()
            if (list.isNotEmpty()) cache.writeText(JSONArray(list.map { JSONObject().put("u", it.url).put("c", it.credit) }).toString())
            list
        } catch (e: Exception) {
            // Нет интернета — используем старый список, если он есть
            if (cache.exists()) parse(cache.readText()) else emptyList()
        }
    }

    private fun parse(json: String): List<Photo> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Photo(o.getString("u"), o.getString("c")) } }
    } catch (e: Exception) {
        emptyList()
    }

    /** Запрос к API Commons: до 50 фото раздела с готовой ссылкой на уменьшенную копию шириной 1920. */
    private fun fetch(source: String): List<Photo> {
        // Случайная буква — чтобы каждый раз получать разные фото из большого раздела
        val prefix = ('A'..'Z').random()
        val title = URLEncoder.encode("Category:$source", "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
            "&generator=categorymembers&gcmtitle=$title&gcmtype=file&gcmlimit=50&gcmstartsortkeyprefix=$prefix" +
            "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiextmetadatafilter=Artist&iiurlwidth=1920"
        val pages = JSONObject(httpGet(url)).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val result = mutableListOf<Photo>()
        for (key in pages.keys()) {
            val info = pages.getJSONObject(key).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val w = info.optInt("width"); val h = info.optInt("height")
            // Только горизонтальные и достаточно большие фото — под экран 16:9
            if (w < 1920 || h == 0 || w.toFloat() / h < 1.3f) continue
            val thumb = info.optString("thumburl")
            if (thumb.isEmpty()) continue
            val artist = info.optJSONObject("extmetadata")?.optJSONObject("Artist")?.optString("value").orEmpty()
            val name = Html.fromHtml(artist, Html.FROM_HTML_MODE_LEGACY).toString().trim().lines().firstOrNull().orEmpty().take(60)
            result += Photo(thumb, if (name.isEmpty()) "Wikimedia Commons" else "$name · Wikimedia Commons")
        }
        return result
    }

    /**
     * Следующие обои: случайное фото категории (скачивается, если его нет в кэше).
     * Без интернета — случайное из уже скачанных. null — если вообще ничего нет.
     */
    fun next(category: Category, maxW: Int, maxH: Int): Wallpaper? {
        val file = try {
            val photo = photos(category).filter { fileFor(it.url) != lastFile }.randomOrNull()
            photo?.let { p ->
                val f = fileFor(p.url)
                if (!f.exists()) {
                    httpDownload(p.url, f)
                    File(dir, f.name + ".txt").writeText(p.credit)
                    trim()
                }
                f
            }
        } catch (e: Exception) {
            null
        } ?: cachedFiles().filter { it != lastFile }.randomOrNull() ?: return null
        return load(file, maxW, maxH)
    }

    /** Случайное уже скачанное фото — мгновенно и без сети (для первого кадра заставки). */
    fun nextCached(maxW: Int, maxH: Int): Wallpaper? {
        val file = cachedFiles().filter { it != lastFile }.randomOrNull() ?: return null
        return load(file, maxW, maxH)
    }

    private fun load(file: File, w: Int, h: Int): Wallpaper? {
        lastFile = file
        file.setLastModified(System.currentTimeMillis())
        val bitmap = try {
            decodeCropped(file, w, h)
        } catch (t: Throwable) {
            // В том числе нехватка памяти — пропускаем это фото
            null
        } ?: return null.also { file.delete() }
        val credit = File(dir, file.name + ".txt").takeIf { it.exists() }?.readText() ?: "Wikimedia Commons"
        return Wallpaper(bitmap, credit)
    }

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }

    private fun cachedFiles() = dir.listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().toList()

    /** Держим на диске не больше [maxFiles] фото — удаляем самые давние. */
    private fun trim() {
        val files = cachedFiles().sortedByDescending { it.lastModified() }
        files.drop(maxFiles).forEach { it.delete(); File(dir, it.name + ".txt").delete() }
    }

    /**
     * Декодируем с уменьшением и сразу обрезаем по центру точно под экран [w]×[h]:
     * меньше памяти (8 МБ вместо 11) и видеочипу не нужно масштабировать лишнее на каждом кадре.
     * Результат изменяемый — в него потом можно дорисовать затемнение.
     */
    private fun decodeCropped(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt(); val ch = (h / scale).toInt()
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }
}

/**
 * Затемнение снизу слева (под часы заставки) рисуется прямо в фото.
 * Так на экране одна текстура, а не фото + полупрозрачный слой поверх на каждом кадре.
 */
fun bakeScreensaverShade(photo: Bitmap, night: Boolean = false) {
    if (!photo.isMutable) return
    val w = photo.width.toFloat()
    val h = photo.height.toFloat()
    // Ночью фото дополнительно приглушаем — чтобы экран не светил в тёмной комнате
    if (night) Canvas(photo).drawColor(Color.argb(120, 0, 0, 0))
    val paint = Paint().apply {
        shader = LinearGradient(0f, h, w * 0.75f, h * 0.15f,
            intArrayOf(Color.argb(215, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    Canvas(photo).drawRect(0f, 0f, w, h, paint)
}

/**
 * Фон главного экрана: фото по размеру экрана + затемнение слева сверху (под часы)
 * и снизу (под плитки). Всё запекается в одну картинку — на экране это одна текстура.
 */
fun composeHomeBackground(photo: Bitmap, w: Int, h: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    // Обрезка по центру (как CENTER_CROP)
    val scale = maxOf(w.toFloat() / photo.width, h.toFloat() / photo.height)
    val cw = (w / scale).toInt(); val ch = (h / scale).toInt()
    val sx = (photo.width - cw) / 2; val sy = (photo.height - ch) / 2
    canvas.drawBitmap(photo, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))

    val paint = Paint()
    canvas.drawColor(Color.argb(25, 0, 0, 0))
    paint.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
        Color.TRANSPARENT, Color.argb(200, 5, 8, 16), Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = LinearGradient(0f, 0f, w * 0.5f, h * 0.35f,
        Color.argb(120, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = LinearGradient(w.toFloat(), 0f, w * 0.6f, h * 0.3f,
        Color.argb(100, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return out
}
