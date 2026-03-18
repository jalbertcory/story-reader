package com.storyreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.storyreader.data.db.dao.BookDao
import com.storyreader.data.db.dao.ReadingPositionDao
import com.storyreader.data.db.dao.ReadingSessionDao
import com.storyreader.data.db.entity.BookEntity
import com.storyreader.data.db.entity.ReadingPositionEntity
import com.storyreader.data.db.entity.ReadingSessionEntity

@Database(
    entities = [BookEntity::class, ReadingPositionEntity::class, ReadingSessionEntity::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun readingPositionDao(): ReadingPositionDao
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reading_sessions ADD COLUMN rawDurationSeconds INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reading_sessions ADD COLUMN wordsRead INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN wordCount INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reading_sessions ADD COLUMN isTts INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE books ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN sourceType TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN serverBookId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN contentVersion INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN contentUpdatedAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN serverWordCount INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN lastSyncedAt INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN lastChapterTitle TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE books ADD COLUMN lastChapterProgression REAL DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "story_reader_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
