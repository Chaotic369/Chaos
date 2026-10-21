package com.chandan.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "aliases")
data class Alias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val telegram: String = "",
    val instagram: String = ""
)

@Entity(tableName = "nicknames")
data class Nickname(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val target: String
)

@Entity(tableName = "activity_log")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val command: String,
    val route: String,
    val action: String,
    val success: Boolean,
    val ms: Long,
    val detail: String = ""
)

@Dao
interface AliasDao {
    @Query("SELECT * FROM aliases ORDER BY name")
    fun all(): Flow<List<Alias>>

    @Query("SELECT * FROM aliases")
    suspend fun list(): List<Alias>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: Alias)

    @Delete
    suspend fun delete(a: Alias)
}

@Dao
interface NicknameDao {
    @Query("SELECT * FROM nicknames ORDER BY name")
    fun all(): Flow<List<Nickname>>

    @Query("SELECT * FROM nicknames")
    suspend fun list(): List<Nickname>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: Nickname)

    @Delete
    suspend fun delete(n: Nickname)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM activity_log ORDER BY time DESC LIMIT 200")
    fun recent(): Flow<List<LogEntry>>

    @Insert
    suspend fun insert(e: LogEntry)

    @Query("DELETE FROM activity_log")
    suspend fun clear()
}

@Entity(tableName = "skills")
data class Skill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val params: String = "",
    val steps: String
)

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY name")
    fun all(): Flow<List<Skill>>

    @Query("SELECT * FROM skills")
    suspend fun list(): List<Skill>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: Skill)

    @Delete
    suspend fun delete(s: Skill)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS skills (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, description TEXT NOT NULL, params TEXT NOT NULL, steps TEXT NOT NULL)"
        )
    }
}

@Entity(tableName = "triggers")
data class Trigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val enabled: Boolean = true,
    val time: String = "",
    val days: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val radius: Float = 150f,
    val event: String = "arrive",
    val notifApp: String = "",
    val notifText: String = "",
    val command: String
)

@Dao
interface TriggerDao {
    @Query("SELECT * FROM triggers ORDER BY name")
    fun all(): Flow<List<Trigger>>

    @Query("SELECT * FROM triggers")
    suspend fun list(): List<Trigger>

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun get(id: Long): Trigger?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: Trigger): Long

    @Delete
    suspend fun delete(t: Trigger)
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS triggers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "name TEXT NOT NULL, type TEXT NOT NULL, enabled INTEGER NOT NULL, time TEXT NOT NULL, " +
                "days TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, radius REAL NOT NULL, " +
                "event TEXT NOT NULL, notifApp TEXT NOT NULL, notifText TEXT NOT NULL, command TEXT NOT NULL)"
        )
    }
}

@Database(entities = [Alias::class, Nickname::class, LogEntry::class, Skill::class, Trigger::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun aliasDao(): AliasDao
    abstract fun nicknameDao(): NicknameDao
    abstract fun logDao(): LogDao
    abstract fun skillDao(): SkillDao
    abstract fun triggerDao(): TriggerDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "assistant.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                .also { inst = it }
        }
    }
}
