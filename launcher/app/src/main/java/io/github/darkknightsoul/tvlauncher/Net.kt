package io.github.darkknightsoul.tvlauncher

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Фоновый поток для быстрых дел: список приложений, чтение с диска. */
val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "home-io") }

/** Поток для вызовов прошивки CVTE (переключение источников, таймер сна) — не ждут загрузки сетки. */
val tv: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "home-tv") }

/** Поток для погоды — короткие запросы, не ждут загрузки фото. */
val net: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "home-net").apply { priority = Thread.MIN_PRIORITY } }

/** Поток для обоев: скачивание и подготовка фото могут идти десятки секунд. */
val wallNet: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "home-wall").apply { priority = Thread.MIN_PRIORITY } }

/**
 * Запуск в фоне с перехватом любых ошибок (включая нехватку памяти).
 * Для главного экрана это важно: падение процесса лаунчера — это цикл перезапусков.
 */
fun ExecutorService.safe(block: () -> Unit) = execute {
    try {
        block()
    } catch (t: Throwable) {
        Log.w("TvHome", "Ошибка в фоновой задаче", t)
    }
}

private const val USER_AGENT = "TvHome/1.0 (home TV launcher; Android 7)"

private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("User-Agent", USER_AGENT)
    }

/** GET-запрос, возвращает текст ответа. */
fun httpGet(url: String): String {
    val c = open(url)
    try {
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode} для $url")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

/** Скачивает файл: сначала во временный, потом переименовывает — чтобы не осталось «половинок». */
fun httpDownload(url: String, target: File) {
    val tmp = File(target.parentFile, target.name + ".part")
    val c = open(url)
    try {
        if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode} для $url")
        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) throw RuntimeException("Не удалось сохранить ${target.name}")
    } finally {
        c.disconnect()
        tmp.delete()
    }
}
