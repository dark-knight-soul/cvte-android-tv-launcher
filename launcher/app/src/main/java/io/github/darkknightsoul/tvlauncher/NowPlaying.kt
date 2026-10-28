package io.github.darkknightsoul.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Пустая служба уведомлений. Сама ничего не делает: Android даёт доступ к списку
 * играющих плееров (MediaSession) только приложениям с включённой такой службой.
 * Включается один раз через adb:
 * settings put secure enabled_notification_listeners io.github.darkknightsoul.tvlauncher/.MediaListener
 */
class MediaListener : NotificationListenerService() {
    /**
     * Сами уведомления нам не нужны: пока служба привязана, система пересылает в наш процесс
     * КАЖДОЕ уведомление любого приложения (с картинками). Для доступа к плеерам достаточно,
     * чтобы служба была разрешена в настройках, поэтому сразу отвязываемся.
     */
    override fun onListenerConnected() {
        requestUnbind()
    }
}

/**
 * Карточка «Сейчас играет»: обложка, название, исполнитель и кнопки назад / пауза / вперёд.
 * Показывается, только когда какой-то плеер (Spotify, YouTube…) что-то играет или стоит на паузе.
 */
class NowPlayingView(context: Context) : LinearLayout(context) {

    private val sessions = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, MediaListener::class.java)
    private var controller: MediaController? = null

    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private val source = TextView(context).apply { style(11f, white(0.6f), "sans-serif-medium"); letterSpacing = 0.08f }
    private val title = TextView(context).apply {
        style(16f, Color.WHITE, "sans-serif-medium"); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = TextView(context).apply {
        style(13f, white(0.75f)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val playPause: RoundButton

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    /**
     * Состояние воспроизведения плееры присылают часто (буферизация, позиция) — на него меняем
     * только значок пауза/плей и видимость. Тексты и обложку — только при смене трека.
     */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshState()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = pick(activeSessions())
    }

    /** Какой трек сейчас показан — чтобы не перерисовывать обложку для той же песни. */
    private var metaKey: String? = null
    private val appNames = HashMap<String, String>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.argb(140, 10, 12, 22)); cornerRadius = context.dp(16).toFloat()
        }
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
        noClip()

        addView(art, LayoutParams(context.dp(64), context.dp(64)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(14), 0, context.dp(10), 0)
            addView(source)
            addView(title, LayoutParams(context.dp(210), LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) })
            addView(artist, LayoutParams(context.dp(210), LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2) })
        })
        val size = context.dp(40)
        addView(RoundButton(context, R.drawable.ic_prev, size).apply {
            setOnClickListener { controller?.transportControls?.skipToPrevious() }
        }, LayoutParams(size, size))
        playPause = RoundButton(context, R.drawable.ic_pause, size).apply {
            setOnClickListener {
                val c = controller ?: return@setOnClickListener
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
            }
        }
        addView(playPause, LayoutParams(size, size).apply { leftMargin = context.dp(8) })
        addView(RoundButton(context, R.drawable.ic_next, size).apply {
            setOnClickListener { controller?.transportControls?.skipToNext() }
        }, LayoutParams(size, size).apply { leftMargin = context.dp(8) })
    }

    /** Начать следить за плеерами (вызывать в onStart). Без доступа к уведомлениям карточка просто скрыта. */
    fun start() {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            pick(activeSessions())
        } catch (e: SecurityException) {
            android.util.Log.w("TvHome", "Нет доступа к плеерам (MediaListener не разрешён)", e)
            visibility = GONE
        }
    }

    fun stop() {
        try { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (e: Exception) { }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun activeSessions(): List<MediaController> =
        try { sessions.getActiveSessions(listener) } catch (e: SecurityException) { emptyList() }

    /** Берём играющий плеер, а если такого нет — любой с данными о треке. */
    private fun pick(list: List<MediaController>?) {
        val all = list.orEmpty()
        // playbackState, а не metadata: metadata тянет через Binder всю обложку только ради проверки
        val best = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.playbackState != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            metaKey = null
            best?.registerCallback(controllerCallback)
        }
        refresh()
    }

    /** Полное обновление: трек (если сменился) + состояние. */
    private fun refresh() {
        val c = controller
        val meta = c?.metadata
        if (c == null || meta == null) {
            metaKey = null
            visibility = GONE
            return
        }
        val trackTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        val trackArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val key = "${c.packageName}|$trackTitle|$trackArtist"
        if (key != metaKey) {
            metaKey = key
            title.text = trackTitle
            artist.text = trackArtist
            val cover: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            // Обложку уменьшаем и скругляем один раз на трек — дальше это готовая маленькая картинка
            art.setImageBitmap(cover?.let {
                renderRounded(android.graphics.drawable.BitmapDrawable(resources, it), context.dp(64), context.dp(64), context.dp(10).toFloat())
            })
            art.visibility = if (cover != null) VISIBLE else GONE
        }
        refreshState()
    }

    /** Лёгкое обновление: пауза/плей, подпись и видимость — без обложки и текстов трека. */
    private fun refreshState() {
        val c = controller ?: return
        val state = c.playbackState?.state
        val active = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED
        if (!active || metaKey == null) {
            if (active && metaKey == null) refresh() else visibility = GONE
            return
        }
        val playing = state == PlaybackState.STATE_PLAYING
        val label = (if (playing) "СЕЙЧАС ИГРАЕТ · " else "НА ПАУЗЕ · ") + appName(c.packageName).uppercase()
        if (source.text != label) source.text = label
        playPause.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        if (visibility != VISIBLE) visibility = VISIBLE
    }

    private fun appName(pkg: String): String = appNames.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
