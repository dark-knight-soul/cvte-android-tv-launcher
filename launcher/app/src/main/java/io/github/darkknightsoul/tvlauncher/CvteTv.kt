package io.github.darkknightsoul.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/** Источник сигнала телевизора (ТВ-антенна, HDMI, AV…) по данным прошивки CVTE. */
class TvSource(
    val id: Int,
    /** Категория из прошивки: INPUTSOURCE_ATV, INPUTSOURCE_DVBT, INPUTSOURCE_HDMI… */
    val category: String,
    val name: String,
    /** Состояние из прошивки: например NORMAL, NO_SIGNAL… */
    val status: String,
) {
    val isHdmi get() = category == "INPUTSOURCE_HDMI"

    /** Эфирное/кабельное ТВ (антенна). */
    val isTv get() = category in TV_CATEGORIES

    companion object {
        val TV_CATEGORIES = setOf(
            "INPUTSOURCE_TV", "INPUTSOURCE_ATV", "INPUTSOURCE_DVBT", "INPUTSOURCE_DVBC",
            "INPUTSOURCE_DVBS", "INPUTSOURCE_ATSC", "INPUTSOURCE_DTMB",
        )
    }
}

/**
 * Работа с ТВ-частью прошивки CVTE — так же, как это делал родной лаунчер.
 *
 * Библиотека CVTE (cn.cvte.tv.api) лежит в системном загрузчике классов, поэтому её классы
 * доступны через рефлексию без встраивания в наш APK. Подключаемся к сервису
 * com.cvte.tv.api.impl/TvApiService и вызываем методы его AIDL-интерфейсов.
 *
 * Полноэкранный просмотр — родной экран CVTE (TvSettingActivity): в нём прошивка сама
 * обрабатывает кнопки Home, Source, каналы. Наш собственный экран с TvView прошивка не знает,
 * и Home в нём перехватывалась службой горячих клавиш CVTE.
 */
object CvteTv {

    private const val TAG = "TvHome"
    private var api: Any? = null
    private var binding = false
    private val waiting = mutableListOf<() -> Unit>()

    val isReady get() = api != null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            api = try {
                Class.forName("com.cvte.tv.api.aidl.ITvApiManager\$Stub")
                    .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            } catch (t: Throwable) {
                Log.w(TAG, "Библиотека CVTE недоступна", t)
                null
            }
            binding = false
            val callbacks = waiting.toList()
            waiting.clear()
            callbacks.forEach { it() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            api = null
        }
    }

    /** Подключиться к сервису (один раз на процесс); [onReady] — когда можно вызывать методы. */
    fun connect(context: Context, onReady: () -> Unit = {}) {
        if (api != null) { onReady(); return }
        waiting += onReady
        if (binding) return
        binding = true
        val intent = Intent("com.cvte.tv.api.TV_API_SERVICE").setPackage("com.cvte.tv.api.impl")
        val ok = try {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            binding = false
            Log.w(TAG, "Не удалось подключиться к TvApiService")
        }
    }

    private fun sourceApi(): Any? = call(api, "getTVApiSystemInputSource")

    /** Список источников из прошивки. Пустой — если сервис недоступен. */
    fun sources(): List<TvSource> {
        val list = call(sourceApi(), "eventSystemInputSourceGetList") as? List<*> ?: return emptyList()
        return list.mapNotNull { e ->
            if (e == null) return@mapNotNull null
            try {
                TvSource(
                    id = field(e, "id") as Int,
                    category = (field(e, "category") as? Enum<*>)?.name.orEmpty(),
                    name = (field(e, "name") as? String).orEmpty(),
                    status = (field(e, "status") as? Enum<*>)?.name.orEmpty(),
                )
            } catch (t: Throwable) {
                null
            }
        }
    }

    /** Текущий источник. */
    fun currentId(): Int? = call(sourceApi(), "eventSystemInputSourceGetInputSource")?.let { field(it, "id") as? Int }

    /** Переключить источник. */
    fun setSource(id: Int): Boolean = call(sourceApi(), "eventSystemInputSourceSetInputSource", id) as? Boolean ?: false

    /** Таймер сна телевизора, минуты (0 — выключен). */
    fun sleepMinutes(): Int? = call(call(api, "getTVApiSystemSleepTime"), "eventSystemSleepTimeGetSleepTime") as? Int

    /** Сколько осталось до выключения по таймеру сна. */
    fun sleepRemaining(): Int? = call(call(api, "getTVApiSystemSleepTime"), "eventSystemSleepTimeGetRemaining") as? Int

    fun setSleepMinutes(minutes: Int): Boolean =
        call(call(api, "getTVApiSystemSleepTime"), "eventSystemSleepTimeSetSleepTime", minutes) as? Boolean ?: false

    /**
     * Выключить телевизор через встроенный таймер сна прошивки (единицы — минуты, проверено на устройстве).
     * Пробуем [minutes]; если прошивка такое значение не принимает — 10 минут. Возвращает, сколько поставилось.
     */
    fun powerOffIn(minutes: Int): Int? {
        for (m in listOf(minutes, 10)) {
            if (setSleepMinutes(m) && sleepMinutes() == m) return m
        }
        return null
    }

    /** Открыть родной полноэкранный ТВ-экран CVTE с текущим источником. */
    fun openTvScreen(context: Context): Boolean = try {
        // Действие MAIN обязательно: экран CVTE в onNewIntent сравнивает action без проверки на null и падает
        context.startActivity(Intent(Intent.ACTION_MAIN)
            .setClassName("com.cvte.tv.androidsetting", "com.cvte.tv.androidsetting.TvSettingActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Не удалось открыть ТВ-экран CVTE", t)
        false
    }

    /** Вызов метода по имени (аргументы — только int). Любая ошибка — null. */
    private fun call(target: Any?, method: String, vararg args: Int): Any? {
        if (target == null) return null
        return try {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            target.javaClass.getMethod(method, *types).invoke(target, *args.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "CVTE: ошибка $method", t)
            null
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getField(name).get(target)
}
