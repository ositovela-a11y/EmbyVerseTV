package com.embyverse.app.data.repo

import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.prefs.PreferencesRepository
import com.embyverse.app.data.prefs.ProfileLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Control parental POR PERFIL. Cada perfil decide qué carpetas oculta y si bloquea contenido para
 * adultos; el desbloqueo con PIN vale solo para ese perfil y hasta cerrar la app.
 * Nada de esto sale del dispositivo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentalControl(private val prefs: PreferencesRepository, db: EmbyDatabase) {
    private val unlocked = MutableStateFlow<Set<Long>>(emptySet())

    val activeLock: Flow<Pair<Long, ProfileLock>> = prefs.activeProfileId.flatMapLatest { id -> prefs.lock(id).map { id to it } }

    /** Carpetas ocultas para el perfil activo en este momento (vacío si no hay bloqueo o ya se desbloqueó). */
    val hidden: Flow<Set<String>> = combine(activeLock, unlocked, db.categories().observeAllNames()) { (id, lock), open, cats ->
        if (id in open || (lock.blocked.isEmpty() && !lock.blockAdult)) emptySet()
        else lock.blocked + (if (lock.blockAdult) cats.filter(::isAdult) else emptyList())
    }.distinctUntilChanged()

    /** Hay contenido bloqueado que se puede desbloquear con el PIN (o ya está desbloqueado). */
    val status: Flow<Status> = combine(activeLock, unlocked) { (id, lock), open ->
        when {
            lock.pin == null || (lock.blocked.isEmpty() && !lock.blockAdult) -> Status.OFF
            id in open -> Status.UNLOCKED
            else -> Status.LOCKED
        }
    }.distinctUntilChanged()

    enum class Status { OFF, LOCKED, UNLOCKED }

    suspend fun checkPin(profile: Long, pin: String): Boolean = prefs.lock(profile).first().pin == pin

    suspend fun unlock(pin: String): Boolean {
        val id = prefs.activeProfile()
        if (!checkPin(id, pin)) return false
        unlocked.value = unlocked.value + id
        return true
    }

    suspend fun relock() { val id = prefs.activeProfile(); unlocked.value = unlocked.value - id }

    companion object {
        private val ADULT = Regex("(adult|adulto|xxx|\\+\\s?18|18\\s?\\+|porn|erotic|erótic|hentai|\\bsexy?\\b|for adults)", RegexOption.IGNORE_CASE)
        fun isAdult(category: String) = ADULT.containsMatchIn(category)
    }
}
