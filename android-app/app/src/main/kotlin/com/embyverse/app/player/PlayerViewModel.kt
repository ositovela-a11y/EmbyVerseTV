package com.embyverse.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EpgEntity
import com.embyverse.app.data.net.Network as Net
import com.embyverse.app.data.prefs.PlaybackPrefs
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.di.AppContainer
import com.embyverse.core.m3u.M3uCatalogBuilder
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lo que se está reproduciendo, ya resuelto desde la base de datos. */
data class NowPlaying(
    val ref: ItemRef,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

sealed interface PlayerUiError {
    val messageRes: Int
    data class Message(override val messageRes: Int, val detail: String? = null) : PlayerUiError
}

@OptIn(UnstableApi::class)
class PlayerViewModel(private val c: AppContainer, startRef: ItemRef, private val context: String?) : ViewModel() {
    val player = MutableStateFlow<ExoPlayer?>(null)
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val error = MutableStateFlow<PlayerUiError?>(null)
    val buffering = MutableStateFlow(true)
    val controlsVisible = MutableStateFlow(false)
    val channelListVisible = MutableStateFlow(false)
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val currentProgram = MutableStateFlow<EpgEntity?>(null)
    val nextEpisode = MutableStateFlow<ItemRef?>(null)
    /** Banner breve con el nombre del canal al cambiar de canal. */
    val zapBanner = MutableStateFlow(false)

    private var ref = startRef
    private var prefs = PlaybackPrefs()
    private var retries = 0
    private var resumeFrom: Long? = null
    private var progressJob: Job? = null
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isLive get() = ref.kind == ContentKind.LIVE

    init {
        viewModelScope.launch {
            prefs = c.prefs.playback.first()
            if (isLive) channels.value = c.database.channels().listForZapping(context?.takeIf { it != "restart" })
            load(ref, firstLoad = true)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
        if (player.value == null) {
            val p = ExoPlayer.Builder(ctx.applicationContext).build().apply {
                addListener(listener)
                playWhenReady = true
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(prefs.preferredAudioLanguage)
                    .setPreferredTextLanguage(prefs.preferredSubtitleLanguage)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesEnabled)
                    .build()
            }
            player.value = p
            nowPlaying.value?.let { prepare(it, resumeFrom) }
        }
        registerNetwork(ctx.applicationContext)
        startProgressTicker()
    }

    fun detach() {
        saveProgress()
        progressJob?.cancel()
        unregisterNetwork()
        player.value?.let { p ->
            if (!isLive) resumeFrom = p.currentPosition
            p.removeListener(listener)
            p.release()
        }
        player.value = null
    }

    override fun onCleared() { detach() }

    // ---------------------------------------------------------------- carga de contenido
    private suspend fun load(target: ItemRef, firstLoad: Boolean = false) {
        error.value = null; buffering.value = true; nextEpisode.value = null
        val np = resolve(target)
        if (np == null) { error.value = PlayerUiError.Message(R.string.player_error_not_found); buffering.value = false; return }
        ref = target
        nowPlaying.value = np
        retries = 0
        resumeFrom = when {
            target.kind == ContentKind.LIVE -> null
            firstLoad && context == "restart" -> 0L
            else -> c.library.progress(target)?.takeIf { !it.completed && it.positionMs > 15_000 }?.positionMs
        }
        if (target.kind == ContentKind.LIVE) {
            c.library.saveProgress(target, np.title, np.imageUrl, 0, 0)
            updateProgram()
        }
        player.value?.let { prepare(np, resumeFrom) }
    }

    private suspend fun resolve(r: ItemRef): NowPlaying? {
        val db = c.database
        return when (r.kind) {
            ContentKind.LIVE -> db.channels().get(r.sourceId, r.id)?.let {
                NowPlaying(r, it.name, it.category, it.logoUrl, c.sources.resolveStreamUrl(r.sourceId, it.streamUrl), it.userAgent, it.referrer)
            }
            ContentKind.MOVIE -> db.movies().get(r.sourceId, r.id)?.let {
                NowPlaying(r, it.title, it.year?.toString(), it.posterUrl, c.sources.resolveStreamUrl(r.sourceId, it.streamUrl), it.userAgent, it.referrer)
            }
            ContentKind.EPISODE -> db.episodes().get(r.sourceId, r.id)?.let { e ->
                val series = db.series().get(r.sourceId, e.seriesProviderId)
                NowPlaying(r, series?.title ?: e.title, "T${e.season} · E${e.episode} — ${e.title}", e.thumbnailUrl ?: series?.posterUrl,
                    c.sources.resolveStreamUrl(r.sourceId, e.streamUrl), e.userAgent, e.referrer, e.seriesProviderId, e.season, e.episode)
            }
            ContentKind.SERIES -> null
        }
    }

    private fun prepare(np: NowPlaying, startAt: Long?) {
        val p = player.value ?: return
        // Cabeceras por stream (User-Agent / Referer declarados en la lista M3U).
        val ds = OkHttpDataSource.Factory(c.okHttp)
            .setUserAgent(np.userAgent ?: Net.USER_AGENT)
            .setDefaultRequestProperties(buildMap { np.referrer?.let { put("Referer", it) } })
        val source = DefaultMediaSourceFactory(ds)
        val ext = M3uCatalogBuilder.extension(np.url)
        val mime = when (ext) {
            "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(np.url).apply { mime?.let { setMimeType(it) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(np.title).setSubtitle(np.subtitle).build())
            .build()
        p.setMediaSource(source.createMediaSource(item))
        if (startAt != null && startAt > 0) p.seekTo(startAt)
        p.prepare()
        p.playWhenReady = true
    }

    fun retry() {
        val np = nowPlaying.value ?: return
        error.value = null; buffering.value = true
        val p = player.value ?: return
        if (isLive) prepare(np, null) else prepare(np, p.currentPosition.takeIf { it > 0 } ?: resumeFrom)
    }

    // ---------------------------------------------------------------- TV en vivo
    fun zap(delta: Int) {
        val list = channels.value
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.sourceId == ref.sourceId && it.providerId == ref.id }
        val next = list[((if (idx < 0) 0 else idx) + delta).mod(list.size)]
        playChannel(next)
    }

    fun playChannel(ch: ChannelEntity) {
        channelListVisible.value = false
        viewModelScope.launch {
            load(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId))
            zapBanner.value = true; delay(3_500); zapBanner.value = false
        }
    }

    fun toggleChannelList() { if (isLive) channelListVisible.value = !channelListVisible.value }

    private suspend fun updateProgram() {
        val ch = c.database.channels().get(ref.sourceId, ref.id) ?: return
        currentProgram.value = ch.epgChannelId?.let { c.epg.nowPlaying(ref.sourceId, listOf(it)).firstOrNull() }
    }

    // ---------------------------------------------------------------- progreso
    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (isLive) updateProgram() else saveProgress()
            }
        }
    }

    private fun saveProgress() {
        val p = player.value ?: return
        val np = nowPlaying.value ?: return
        if (isLive || np.ref.kind == ContentKind.SERIES) return
        val pos = p.currentPosition; val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        viewModelScope.launch { c.library.saveProgress(np.ref, np.title, np.imageUrl, pos, dur, np.seriesId, np.season, np.episode) }
    }

    private fun onEnded() {
        val np = nowPlaying.value ?: return
        saveProgress()
        if (np.ref.kind != ContentKind.EPISODE || np.seriesId == null) return
        viewModelScope.launch {
            val eps = c.database.episodes().forSeries(np.ref.sourceId, np.seriesId)
            val idx = eps.indexOfFirst { it.providerId == np.ref.id }
            val next = eps.getOrNull(idx + 1) ?: return@launch
            val nextRef = ItemRef(ContentKind.EPISODE, next.sourceId, next.providerId)
            nextEpisode.value = nextRef
            if (prefs.autoplayNextEpisode) { delay(5_000); if (nextEpisode.value == nextRef) playNext() }
        }
    }

    fun playNext() { val n = nextEpisode.value ?: return; nextEpisode.value = null; viewModelScope.launch { load(n) } }

    // ---------------------------------------------------------------- errores y reconexión
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && error.value == null
            if (state == Player.STATE_READY) { retries = 0; error.value = null }
            if (state == Player.STATE_ENDED) { buffering.value = false; onEnded() }
        }

        override fun onPlayerError(e: PlaybackException) {
            buffering.value = false
            val p = player.value
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            val transient = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            if (transient && prefs.autoReconnect && retries < 3) {
                retries++
                viewModelScope.launch { delay(1_000L shl retries); retry() }
                return
            }
            error.value = PlayerUiError.Message(messageFor(e), e.errorCodeName)
        }
    }

    private fun messageFor(e: PlaybackException): Int {
        val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            http == 401 || http == 403 -> R.string.player_error_forbidden
            http == 404 || http == 410 -> R.string.player_error_not_found
            http != null && http >= 500 -> R.string.player_error_server
            e.errorCode in 6000..6999 -> R.string.player_error_drm
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> R.string.player_error_network
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> R.string.player_error_format
            else -> R.string.player_error_generic
        }
    }

    /** Si se recupera la red y el reproductor quedó en error, se reintenta automáticamente. */
    private fun registerNetwork(ctx: Context) {
        if (networkCallback != null) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch { if (error.value != null && prefs.autoReconnect) retry() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun unregisterNetwork() {
        val cb = networkCallback ?: return
        appContext?.getSystemService(ConnectivityManager::class.java)?.let { runCatching { it.unregisterNetworkCallback(cb) } }
        networkCallback = null
    }
}
