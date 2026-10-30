package com.embyverse.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class, ChannelEntity::class, MovieEntity::class, SeriesEntity::class, EpisodeEntity::class,
        CategoryEntity::class, ProfileEntity::class, FavoriteEntity::class, ProgressEntity::class, EpgEntity::class,
    ],
    version = 2,
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
                // Nunca se usa fallbackToDestructiveMigration para no borrar favoritos ni progreso.
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: índices por posición para abrir listas grandes (decenas de miles) sin ordenar en memoria. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("channels", "movies", "series")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${t}_sourceId_position` ON `$t` (`sourceId`, `position`)")
                }
            }
        }
    }
}
