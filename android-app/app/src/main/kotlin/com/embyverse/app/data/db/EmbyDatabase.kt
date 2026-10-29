package com.embyverse.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        CategoryEntity::class, ProfileEntity::class, FavoriteEntity::class, ProgressEntity::class, EpgEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class EmbyDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun channels(): ChannelDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun categories(): CategoryDao
    abstract fun profiles(): ProfileDao
    abstract fun library(): LibraryDao
    abstract fun epg(): EpgDao

    companion object {
        fun build(context: Context): EmbyDatabase =
            Room.databaseBuilder(context, EmbyDatabase::class.java, "embyverse.db")
                // Versión 1: las futuras versiones deben añadir Migration(...) explícitas;
                // nunca se usa fallbackToDestructiveMigration para no borrar favoritos ni progreso.
                .build()
    }
}
