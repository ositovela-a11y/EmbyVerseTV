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
    version = 5,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /**
         * v2: índice por posición para abrir listas grandes (decenas de miles) sin ordenar en memoria, y
         * se quita el índice de búsqueda: las búsquedas usan LIKE '%texto%', que no puede aprovecharlo,
         * y solo frenaba el guardado de las listas.
         */
        /** v5: clasificación por edad de las películas. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE `movies` ADD COLUMN `ageRating` TEXT DEFAULT NULL") }
        }

        /** v4: índice por carpeta + orden: abrir una carpeta ya no recorre toda la lista. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("channels", "movies", "series"))
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${t}_category_sourceId_position` ON `$t` (`category`, `sourceId`, `position`)")
            }
        }

        /** v3: tráiler de las películas. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `trailer` TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (t in listOf("channels", "movies", "series")) {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${t}_sourceId_position` ON `$t` (`sourceId`, `position`)")
                    db.execSQL("DROP INDEX IF EXISTS `index_${t}_searchKey`")
                }
            }
        }
    }
}
