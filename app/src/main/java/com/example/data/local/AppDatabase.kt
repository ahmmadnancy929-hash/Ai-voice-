package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProcessedTrackEntity::class, EnhancedVoiceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedTrackDao(): ProcessedTrackDao
    abstract fun enhancedVoiceDao(): EnhancedVoiceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enhanced_voices` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceFormat` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sampleRate` INTEGER NOT NULL,
                        `channels` INTEGER NOT NULL,
                        `fileSizeBytes` INTEGER NOT NULL,
                        `strengthName` TEXT NOT NULL,
                        `originalPath` TEXT NOT NULL,
                        `enhancedPath` TEXT NOT NULL,
                        `originalWaveformCsv` TEXT NOT NULL,
                        `enhancedWaveformCsv` TEXT NOT NULL,
                        `engineUsed` TEXT NOT NULL,
                        `isRemoteMp3Available` INTEGER NOT NULL DEFAULT 0,
                        `remoteEnhancedMp3Path` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vocal_remover_studio.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
