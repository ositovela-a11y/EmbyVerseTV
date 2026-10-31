package com.embyverse.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EpgEntity
import com.embyverse.app.data.prefs.PlaybackPrefs
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.di.AppContainer
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

/** Estado observable del reproductor para las pruebas en dispositivo (sin datos personales). */
object PlaybackProbe {
    @Volatile var readyCount = 0
    @Volatile var lastError: String? = null
    @Volatile var lastKey: String? = null
    fun reset() { readyCount = 0; lastError = null }
}

/** Pista de audio o subtítulos que se puede elegir en el panel de opciones. */
data class TrackOption(val key: String, val label: String, val selected: Boolean, internal val group: Tracks.Group?, internal val index: Int)

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
    /** Panel de opciones (audio, subtítulos, lista de canales). Se abre con OK en vivo o con MENÚ. */
    val optionsVisible = MutableStateFlow(false)
    /** En ventana «imagen en imagen»: se ocultan todos los controles. */
    val inPip = MutableStateFlow(false)
    val audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val textTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    val textEnabled = MutableStateFlow(false)
    /** Canales de la carpeta que se está viendo en la lista (puede ser otra distinta a la del canal actual). */
    val channels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    /** Carpetas de TV en vivo; null = todas. */
    val categories = MutableStateFlow<List<String>>(emptyList())
    val listCategory = MutableStateFlow<String?>(null)
    /** Canales para ▲▼: los de la carpeta del canal que se está viendo. */
    private var zapList: List<ChannelEntity> = emptyList()
    val currentProgram = MutableStateFlow<EpgEntity?>(null)
    val nextEpisode = MutableStateFlow<ItemRef?>(null)
    /** Siguiente episodio (para la cuenta regresiva al final del actual). Declarado antes de init: load() lo usa. */
    val upNext = MutableStateFlow<Pair<ItemRef, String>?>(null)
    val upNextCancelled = MutableStateFlow(false)
    /** Banner breve con el nombre del canal al cambiar de canal. */
    val zapBanner = MutableStateFlow(false)

    private var ref = startRef
    private var prefs = PlaybackPrefs()
    private var retries = 0
    /** Formatos alternativos ya probados para el canal actual (ver StreamSource.alternatives). */
    private var altIndex = -1
    private var forcedMime: String? = null
    private var originalUrl: String? = null
    private var resumeFrom: Long? = null
    private var progressJob: Job? = null
    private var appContext: Context? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val isLive get() = ref.kind == ContentKind.LIVE

    init {
        viewModelScope.launch {
            prefs = c.prefs.playback.first()
            if (isLive) {
                val cat = context?.takeIf { it != "restart" }
                listCategory.value = cat
                channels.value = visible(c.database.channels().listForZapping(cat))
                zapList = channels.value
                launch { categories.value = c.catalog.categories(ContentKind.LIVE).first() }
            }
            load(ref, firstLoad = true)
            // En vivo: al entrar se muestra un momento el nombre del canal y cómo cambiar de canal.
            if (isLive) { zapBanner.value = true; delay(4_500); zapBanner.value = false }
        }
    }

    // ---------------------------------------------------------------- ciclo de vida
    fun attach(ctx: Context) {
        appContext = ctx.applicationContext
        if (player.value == null) {
            val p = StreamSource.player(ctx, quickStart = false).apply {
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
        ref = np.ref
        nowPlaying.value = np
        computeUpNext(np)
        retries = 0; altIndex = -1; forcedMime = null; originalUrl = null
        resumeFrom = when {
            np.ref.kind == ContentKind.LIVE -> null
            firstLoad && context == "restart" -> 0L
            else -> c.library.progress(np.ref)?.takeIf { !it.completed && it.positionMs > 15_000 }?.positionMs
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
            // Serie (p. ej. «Ver ahora» en las novedades): se sigue donde se quedó o se empieza por el primer episodio.
            ContentKind.SERIES -> {
                val series = db.series().get(r.sourceId, r.id) ?: return null
                var eps = db.episodes().forSeries(r.sourceId, r.id)
                if (eps.isEmpty()) { c.sources.loadEpisodes(r.sourceId, r.id); eps = db.episodes().forSeries(r.sourceId, r.id) }
                if (eps.isEmpty()) return null
                val progress = c.library.episodeProgress(r.sourceId, r.id).first().sortedByDescending { it.updatedAt }
                val last = progress.firstOrNull()
                val target = when {
                    last == null -> eps.first()
                    !last.completed -> eps.firstOrNull { it.providerId == last.itemId } ?: eps.first()
                    else -> eps.getOrNull(eps.indexOfFirst { it.providerId == last.itemId } + 1) ?: eps.first()
                }
                resolve(ItemRef(ContentKind.EPISODE, target.sourceId, target.providerId))
            }
        }
    }

    private fun prepare(np: NowPlaying, startAt: Long?) {
        val p = player.value ?: return
        p.setMediaSource(StreamSource.mediaSource(c.okHttp, np.url, np.userAgent, np.referrer, np.title, np.subtitle, forcedMime))
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
        val list = zapList.ifEmpty { channels.value }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.sourceId == ref.sourceId && it.providerId == ref.id }
        val next = list[((if (idx < 0) 0 else idx) + delta).mod(list.size)]
        playChannel(next)
    }

    fun playChannel(ch: ChannelEntity) {
        channelListVisible.value = false
        // Al elegir de otra carpeta, ▲▼ siguen esa carpeta.
        zapList = channels.value
        viewModelScope.launch {
            load(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId))
            zapBanner.value = true; delay(3_500); zapBanner.value = false
        }
    }

    private var categoryJob: Job? = null

    /** El control parental del perfil también aplica a la lista de canales del reproductor. */
    private suspend fun visible(list: List<ChannelEntity>): List<ChannelEntity> {
        val h = c.parental.hidden.first()
        return if (h.isEmpty()) list else list.filter { it.category !in h }
    }

    /** ◀ ▶ dentro de la lista de canales: carpeta anterior / siguiente (Todos, Entretenimiento, Premium…). */
    fun shiftListCategory(delta: Int) {
        val options = listOf<String?>(null) + categories.value
        if (options.size < 2) return
        val idx = options.indexOf(listCategory.value).coerceAtLeast(0)
        val target = options[(idx + delta).mod(options.size)]
        listCategory.value = target
        categoryJob?.cancel()
        categoryJob = viewModelScope.launch {
            delay(120) // si se pulsa varias veces seguidas solo se consulta la última carpeta
            channels.value = visible(c.database.channels().listForZapping(target))
        }
    }

    fun showOptions(show: Boolean) {
        if (show) { channelListVisible.value = false; controlsVisible.value = false; refreshTracks() }
        optionsVisible.value = show
    }

    fun openChannelList() {
        if (!isLive) return
        optionsVisible.value = false; controlsVisible.value = false
        channelListVisible.value = true
    }

    // ---------------------------------------------------------------- audio y subtítulos
    private fun refreshTracks() {
        val p = player.value ?: run { audioTracks.value = emptyList(); textTracks.value = emptyList(); return }
        val tracks = p.currentTracks
        val audio = ArrayList<TrackOption>(); val text = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { gi, g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackOption("a$gi-$i", trackLabel(f, audio.size + 1, true), g.isTrackSelected(i), g, i)
                    C.TRACK_TYPE_TEXT -> text += TrackOption("t$gi-$i", trackLabel(f, text.size + 1, false), g.isTrackSelected(i), g, i)
                }
            }
        }
        audioTracks.value = audio
        textTracks.value = text
        textEnabled.value = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && text.any { it.selected }
    }

    private fun trackLabel(f: androidx.media3.common.Format, n: Int, audio: Boolean): String {
        val lang = f.language?.takeIf { it != C.LANGUAGE_UNDETERMINED && it.isNotBlank() }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { c -> c.uppercase() } }
        val base = f.label ?: lang ?: "#$n"
        val extra = buildList {
            if (f.label != null && lang != null && !f.label!!.contains(lang, true)) add(lang)
            if (audio) when (f.channelCount) { 1 -> add("Mono"); 2 -> add("Estéreo"); 6 -> add("5.1"); 8 -> add("7.1") }
        }
        return if (extra.isEmpty()) base else "$base · ${extra.joinToString(" · ")}"
    }

    fun selectTrack(option: TrackOption) {
        val p = player.value ?: return
        val g = option.group ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.index))
            .build()
    }

    fun subtitlesOff() {
        val p = player.value ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
    }

    fun toggleChannelList() { if (!isLive) return; if (channelListVisible.value) channelListVisible.value = false else openChannelList() }

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

    val autoplayNext get() = prefs.autoplayNextEpisode

    private suspend fun computeUpNext(np: NowPlaying) {
        upNextCancelled.value = false
        if (np.ref.kind != ContentKind.EPISODE || np.seriesId == null) { upNext.value = null; return }
        val eps = c.database.episodes().forSeries(np.ref.sourceId, np.seriesId)
        val next = eps.getOrNull(eps.indexOfFirst { it.providerId == np.ref.id } + 1)
        upNext.value = next?.let { ItemRef(ContentKind.EPISODE, it.sourceId, it.providerId) to "T${it.season} · E${it.episode} — ${it.title}" }
    }

    fun cancelUpNext() { upNextCancelled.value = true }
    fun playUpNext() { val n = upNext.value?.first ?: return; upNext.value = null; viewModelScope.launch { load(n) } }

    private fun onEnded() {
        val np = nowPlaying.value ?: return
        saveProgress()
        // Terminó la cuenta regresiva: sigue el próximo episodio sin esperar más.
        if (upNext.value != null && prefs.autoplayNextEpisode && !upNextCancelled.value) { playUpNext(); return }
        if (np.ref.kind != ContentKind.EPISODE || np.seriesId == null) return
        viewModelScope.launch {
            val eps = c.database.episodes().forSeries(np.ref.sourceId, np.seriesId)
            val idx = eps.indexOfFirst { it.providerId == np.ref.id }
            val next = eps.getOrNull(idx + 1) ?: return@launch
            nextEpisode.value = ItemRef(ContentKind.EPISODE, next.sourceId, next.providerId)
        }
    }

    fun playNext() { val n = nextEpisode.value ?: return; nextEpisode.value = null; viewModelScope.launch { load(n) } }

    // ---------------------------------------------------------------- errores y reconexión
    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) { refreshTracks() }
        override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) { refreshTracks() }
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && error.value == null
            if (state == Player.STATE_READY) { retries = 0; error.value = null; PlaybackProbe.readyCount++ }
            if (state == Player.STATE_ENDED) { buffering.value = false; onEnded() }
        }

        override fun onPlayerError(e: PlaybackException) {
            buffering.value = false
            val p = player.value
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            // El proveedor entregó otro formato del anunciado: se prueba HLS / .ts automáticamente.
            val formatProblem = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                (isLive && e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            val cur = nowPlaying.value
            if (formatProblem && cur != null) {
                val alts = StreamSource.alternatives(originalUrl ?: cur.url)
                if (originalUrl == null) originalUrl = cur.url
                if (altIndex + 1 < alts.size) {
                    altIndex++
                    val (u, m) = alts[altIndex]
                    forcedMime = m
                    val np = cur.copy(url = u)
                    nowPlaying.value = np
                    error.value = null; buffering.value = true
                    prepare(np, if (isLive) null else p?.currentPosition)
                    return
                }
            }
            val transient = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            if (transient && prefs.autoReconnect && retries < 3) {
                retries++
                viewModelScope.launch { delay(1_000L shl retries); retry() }
                return
            }
            PlaybackProbe.lastError = e.errorCodeName
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
