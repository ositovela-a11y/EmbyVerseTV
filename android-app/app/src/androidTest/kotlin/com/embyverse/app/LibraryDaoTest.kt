package com.embyverse.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.db.FavoriteEntity
import com.embyverse.app.data.db.ProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EmbyDatabase::class.java).build()
    private val lib = db.library()
    @After fun close() = db.close()

    @Test fun favoritosPorPerfil() = runBlocking {
        lib.addFavorite(FavoriteEntity(1, "MOVIE", "s", "m1", "Película", null, 1))
        assertTrue(lib.observeIsFavorite(1, "MOVIE", "s", "m1").first())
        assertFalse("otro perfil no ve el favorito", lib.observeIsFavorite(2, "MOVIE", "s", "m1").first())
        lib.removeFavorite(1, "MOVIE", "s", "m1")
        assertFalse(lib.observeIsFavorite(1, "MOVIE", "s", "m1").first())
    }

    @Test fun continuarViendoExcluyeTerminadosYEnVivo() = runBlocking {
        fun p(id: String, kind: String, pos: Long, done: Boolean, at: Long) = ProgressEntity(1, kind, "s", id, id, null, pos, 100_000, done, at)
        lib.saveProgress(p("a", "MOVIE", 40_000, false, 1))
        lib.saveProgress(p("b", "MOVIE", 99_000, true, 2))
        lib.saveProgress(p("c", "EPISODE", 50_000, false, 3))
        lib.saveProgress(p("d", "LIVE", 0, false, 4))
        lib.saveProgress(p("e", "MOVIE", 5_000, false, 5)) // menos de 15 s: no cuenta
        assertEquals(listOf("c", "a"), lib.observeContinueWatching(1, 10).first().map { it.itemId })
        lib.saveProgress(p("a", "MOVIE", 60_000, false, 9)) // actualizar no duplica
        assertEquals(listOf("a", "c"), lib.observeContinueWatching(1, 10).first().map { it.itemId })
    }
}
