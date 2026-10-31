package com.embyverse.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.embyverse.core.config.CardSize
import com.embyverse.core.config.UserAppearance
import com.embyverse.core.xtream.LiveFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "embyverse_prefs")

data class PlaybackPrefs(
    val liveFormat: LiveFormat = LiveFormat.TS,
    val lockLandscape: Boolean = true,
    val autoplayNextEpisode: Boolean = true,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val subtitlesEnabled: Boolean = false,
    val autoReconnect: Boolean = true,
    /** Sonido corto al abrir la app. */
    val introSound: Boolean = true,
    /** Minutos que la pantalla sigue encendida en los menús sin tocar el control (-1 = siempre). */
    val menuAwakeMinutes: Int = 120,
    /** Clic suave al navegar con el control (solo TV). */
    val navSounds: Boolean = true,
    /** Protector de pantalla propio en los menús tras 5 min sin usar el control. */
    val screensaver: Boolean = true,
)

/**
 * Seguridad de UN perfil: PIN, carpetas bloqueadas, bloqueo automático de adultos y avatar.
 * Cada perfil tiene la suya: lo que bloquea un perfil (p. ej. «Niños») no afecta a los demás.
 */
data class ProfileLock(
    val pin: String? = null,
    val pinOnEnter: Boolean = false,
    val blocked: Set<String> = emptySet(),
    val blockAdult: Boolean = false,
    val avatar: String? = null,
    /** Perfil infantil: solo carpetas para niños, colores alegres y PIN para salir. */
    val kids: Boolean = false,
)

/** Preferencias locales del dispositivo. Nada de esto se envía a ningún servidor. */
class PreferencesRepository(private val context: Context) {
    private val ds get() = context.dataStore
    private val ACTIVE_PROFILE = longPreferencesKey("active_profile")

    private fun k(profile: Long, name: String) = "p$profile.$name"

    val activeProfileId: Flow<Long> = ds.data.map { it[ACTIVE_PROFILE] ?: 1L }.distinctUntilChanged()
    suspend fun activeProfile(): Long = activeProfileId.first()
    suspend fun setActiveProfile(id: Long) { ds.edit { it[ACTIVE_PROFILE] = id } }

    fun appearance(profile: Long): Flow<UserAppearance> = ds.data.map { p ->
        UserAppearance(
            themeId = p[stringPreferencesKey(k(profile, "theme"))],
            accentColor = p[intPreferencesKey(k(profile, "accent"))],
            cardSize = p[stringPreferencesKey(k(profile, "cardSize"))]?.let { s -> CardSize.entries.firstOrNull { it.wire == s } },
            hiddenSections = p[stringSetPreferencesKey(k(profile, "hidden"))].orEmpty(),
            sectionOrder = p[stringPreferencesKey(k(profile, "order"))]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }.distinctUntilChanged()

    suspend fun updateAppearance(profile: Long, transform: (UserAppearance) -> UserAppearance) {
        val current = appearance(profile).first()
        val next = transform(current)
        ds.edit { p ->
            fun <T> set(key: Preferences.Key<T>, v: T?) { if (v == null) p.remove(key) else p[key] = v }
            set(stringPreferencesKey(k(profile, "theme")), next.themeId)
            set(intPreferencesKey(k(profile, "accent")), next.accentColor)
            set(stringPreferencesKey(k(profile, "cardSize")), next.cardSize?.wire)
            set(stringSetPreferencesKey(k(profile, "hidden")), next.hiddenSections.ifEmpty { null })
            set(stringPreferencesKey(k(profile, "order")), next.sectionOrder.joinToString(",").ifEmpty { null })
        }
    }

    suspend fun resetAppearance(profile: Long) = updateAppearance(profile) { UserAppearance.ORIGINAL }

    private val LIVE_FORMAT = stringPreferencesKey("play.liveFormat")
    private val LOCK_LANDSCAPE = booleanPreferencesKey("play.lockLandscape")
    private val AUTOPLAY_NEXT = booleanPreferencesKey("play.autoplayNext")
    private val AUDIO_LANG = stringPreferencesKey("play.audioLang")
    private val SUB_LANG = stringPreferencesKey("play.subLang")
    private val SUBS_ON = booleanPreferencesKey("play.subsOn")
    private val RECONNECT = booleanPreferencesKey("play.reconnect")
    private val INTRO_SOUND = booleanPreferencesKey("app.introSound")
    private val MENU_AWAKE = intPreferencesKey("app.menuAwake")
    private val NAV_SOUNDS = booleanPreferencesKey("app.navSounds")
    private val SCREENSAVER = booleanPreferencesKey("app.screensaver")

    val playback: Flow<PlaybackPrefs> = ds.data.map { p ->
        PlaybackPrefs(
            liveFormat = p[LIVE_FORMAT]?.let { runCatching { LiveFormat.valueOf(it) }.getOrNull() } ?: LiveFormat.TS,
            lockLandscape = p[LOCK_LANDSCAPE] ?: true,
            autoplayNextEpisode = p[AUTOPLAY_NEXT] ?: true,
            preferredAudioLanguage = p[AUDIO_LANG],
            preferredSubtitleLanguage = p[SUB_LANG],
            subtitlesEnabled = p[SUBS_ON] ?: false,
            autoReconnect = p[RECONNECT] ?: true,
            introSound = p[INTRO_SOUND] ?: true,
            menuAwakeMinutes = p[MENU_AWAKE] ?: 120,
            navSounds = p[NAV_SOUNDS] ?: true,
            screensaver = p[SCREENSAVER] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun updatePlayback(transform: (PlaybackPrefs) -> PlaybackPrefs) {
        val n = transform(playback.first())
        ds.edit { p ->
            p[LIVE_FORMAT] = n.liveFormat.name
            p[LOCK_LANDSCAPE] = n.lockLandscape
            p[AUTOPLAY_NEXT] = n.autoplayNextEpisode
            if (n.preferredAudioLanguage == null) p.remove(AUDIO_LANG) else p[AUDIO_LANG] = n.preferredAudioLanguage
            if (n.preferredSubtitleLanguage == null) p.remove(SUB_LANG) else p[SUB_LANG] = n.preferredSubtitleLanguage
            p[SUBS_ON] = n.subtitlesEnabled
            p[RECONNECT] = n.autoReconnect
            p[INTRO_SOUND] = n.introSound
            p[MENU_AWAKE] = n.menuAwakeMinutes
            p[NAV_SOUNDS] = n.navSounds
            p[SCREENSAVER] = n.screensaver
        }
    }

    fun lock(profile: Long): Flow<ProfileLock> = ds.data.map { p ->
        ProfileLock(
            pin = p[stringPreferencesKey(k(profile, "pin"))],
            pinOnEnter = p[booleanPreferencesKey(k(profile, "pinOnEnter"))] ?: false,
            blocked = p[stringSetPreferencesKey(k(profile, "blocked"))].orEmpty(),
            blockAdult = p[booleanPreferencesKey(k(profile, "blockAdult"))] ?: false,
            avatar = p[stringPreferencesKey(k(profile, "avatar"))],
            kids = p[booleanPreferencesKey(k(profile, "kids"))] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun updateLock(profile: Long, transform: (ProfileLock) -> ProfileLock) {
        val n = transform(lock(profile).first())
        ds.edit { p ->
            fun <T> set(key: Preferences.Key<T>, v: T?) { if (v == null) p.remove(key) else p[key] = v }
            set(stringPreferencesKey(k(profile, "pin")), n.pin)
            set(booleanPreferencesKey(k(profile, "pinOnEnter")), n.pinOnEnter.takeIf { it })
            set(stringSetPreferencesKey(k(profile, "blocked")), n.blocked.ifEmpty { null })
            set(booleanPreferencesKey(k(profile, "blockAdult")), n.blockAdult.takeIf { it })
            set(stringPreferencesKey(k(profile, "avatar")), n.avatar)
            set(booleanPreferencesKey(k(profile, "kids")), n.kids.takeIf { it })
        }
    }

    private val SEEN_NOTICES = stringSetPreferencesKey("app.seenNotices")
    val seenNotices: Flow<Set<String>> = ds.data.map { it[SEEN_NOTICES].orEmpty() }.distinctUntilChanged()
    suspend fun markNoticeSeen(id: String) { ds.edit { it[SEEN_NOTICES] = it[SEEN_NOTICES].orEmpty() + id } }

    /** Vencimiento de cada cuenta (segundos), guardado en cada actualización de la lista. */
    val sourceExpiries: Flow<Map<String, Long>> = ds.data.map { p ->
        p.asMap().entries.filter { it.key.name.startsWith("src.exp.") }.associate { it.key.name.removePrefix("src.exp.") to (it.value as Long) }
    }.distinctUntilChanged()

    suspend fun setSourceExpiry(sourceId: String, epochSeconds: Long?) {
        val key = longPreferencesKey("src.exp.$sourceId")
        ds.edit { if (epochSeconds == null || epochSeconds <= 0) it.remove(key) else it[key] = epochSeconds }
    }

    /** Borra las preferencias de un perfil eliminado. */
    suspend fun deleteProfile(profile: Long) {
        @Suppress("UNCHECKED_CAST")
        ds.edit { p -> p.asMap().keys.filter { it.name.startsWith("p$profile.") }.forEach { p.remove(it as Preferences.Key<Any>) } }
    }
}
