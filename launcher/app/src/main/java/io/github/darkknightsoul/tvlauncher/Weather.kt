package io.github.darkknightsoul.tvlauncher

import android.content.Context
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.roundToInt

/** Текущая погода и сведения о дне. */
class WeatherInfo(
    val city: String,
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val max: Int,
    val min: Int,
    /** Время заката «ЧЧ:ММ». */
    val sunset: String,
) {
    val emoji: String get() = weatherEmoji(code, isDay)
    val description: String get() = weatherText(code)
    val tempText: String get() = signed(temp)

    fun toJson(): String = JSONObject()
        .put("city", city).put("temp", temp).put("code", code).put("day", isDay)
        .put("max", max).put("min", min).put("sunset", sunset).toString()

    companion object {
        fun fromJson(s: String) = JSONObject(s).let {
            WeatherInfo(it.getString("city"), it.getInt("temp"), it.getInt("code"), it.getBoolean("day"),
                it.getInt("max"), it.getInt("min"), it.getString("sunset"))
        }
    }
}

fun signed(t: Int) = if (t > 0) "+$t°" else "$t°"

/**
 * Погода с Open-Meteo (бесплатно, без ключа). Город — по IP через ipwho.is (раз в сутки),
 * координаты кэшируются. Погода кэшируется на 30 минут. Вызывать из фонового потока.
 */
class Weather(context: Context) {

    private val sp = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** Последняя сохранённая погода (может быть старой) — для мгновенного показа. */
    fun cached(): WeatherInfo? = sp.getString("info", null)?.let {
        try { WeatherInfo.fromJson(it) } catch (e: Exception) { null }
    }

    /** Нужно ли обновить: прошло больше 30 минут. */
    fun stale(): Boolean = System.currentTimeMillis() - sp.getLong("time", 0) > 30 * 60_000L

    fun refresh(): WeatherInfo? = try {
        val (city, lat, lon) = location() ?: throw RuntimeException("Город не определён")
        val json = JSONObject(httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,sunset&timezone=auto&forecast_days=1"
        ))
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val info = WeatherInfo(
            city = city,
            temp = cur.getDouble("temperature_2m").roundToInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            max = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            sunset = daily.getJSONArray("sunset").getString(0).substringAfter('T').take(5),
        )
        sp.edit().putString("info", info.toJson()).putLong("time", System.currentTimeMillis()).apply()
        info
    } catch (e: Exception) {
        null
    }

    /** Город и координаты по IP; кэш на сутки, без сети — последний известный. */
    private fun location(): Triple<String, Double, Double>? {
        val fresh = System.currentTimeMillis() - sp.getLong("loc_time", 0) < 24 * 3600_000L
        if (fresh && sp.contains("city")) return savedLocation()
        return try {
            val j = JSONObject(httpGet("https://ipwho.is/?lang=ru&fields=success,city,latitude,longitude"))
            if (!j.optBoolean("success", false)) throw RuntimeException("ipwho.is: нет данных")
            val loc = Triple(j.getString("city"), j.getDouble("latitude"), j.getDouble("longitude"))
            sp.edit().putString("city", loc.first).putFloat("lat", loc.second.toFloat())
                .putFloat("lon", loc.third.toFloat()).putLong("loc_time", System.currentTimeMillis()).apply()
            loc
        } catch (e: Exception) {
            if (sp.contains("city")) savedLocation() else null
        }
    }

    private fun savedLocation() =
        Triple(sp.getString("city", "")!!, sp.getFloat("lat", 0f).toDouble(), sp.getFloat("lon", 0f).toDouble())
}

/** Значок погоды по коду WMO. */
fun weatherEmoji(code: Int, day: Boolean) = when (code) {
    0 -> if (day) "☀️" else "🌙"
    1 -> if (day) "🌤" else "🌙"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫"
    51, 53, 55 -> "🌦"
    56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧"
    71, 73, 75, 77, 85, 86 -> "🌨"
    95, 96, 99 -> "⛈"
    else -> "🌡"
}

/** Описание погоды по коду WMO. */
fun weatherText(code: Int) = when (code) {
    0 -> "Ясно"
    1 -> "Почти ясно"
    2 -> "Переменная облачность"
    3 -> "Пасмурно"
    45, 48 -> "Туман"
    51, 53, 55 -> "Морось"
    56, 57 -> "Ледяная морось"
    61 -> "Небольшой дождь"
    63 -> "Дождь"
    65 -> "Сильный дождь"
    66, 67 -> "Ледяной дождь"
    71 -> "Небольшой снег"
    73 -> "Снег"
    75 -> "Сильный снег"
    77 -> "Снежная крупа"
    80, 81 -> "Ливень"
    82 -> "Сильный ливень"
    85, 86 -> "Снегопад"
    95 -> "Гроза"
    96, 99 -> "Гроза с градом"
    else -> ""
}

/** Фаза луны: значок, название и освещённость в процентах. */
class MoonPhase(val emoji: String, val name: String, val percent: Int)

fun moonPhase(timeMs: Long = System.currentTimeMillis()): MoonPhase {
    val synodic = 29.530588853
    // Опорное новолуние: 6 января 2000, 18:14 UTC
    val days = (timeMs - 947182440000L) / 86_400_000.0
    val age = ((days % synodic) + synodic) % synodic
    val percent = ((1 - cos(2 * Math.PI * age / synodic)) / 2 * 100).roundToInt()
    val index = ((age / synodic * 8) + 0.5).toInt() % 8
    val emoji = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")[index]
    val name = listOf("Новолуние", "Растущая луна", "Первая четверть", "Растущая луна",
        "Полнолуние", "Убывающая луна", "Последняя четверть", "Убывающая луна")[index]
    return MoonPhase(emoji, name, percent)
}
