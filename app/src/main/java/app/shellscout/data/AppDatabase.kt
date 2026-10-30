package app.shellscout.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY groupName COLLATE NOCASE, name COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostEntity?

    @Insert
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("UPDATE hosts SET lastConnectedAt = :at WHERE id = :id")
    suspend fun markConnected(id: Long, at: Long)

    @Query("UPDATE hosts SET wgConfigId = NULL WHERE wgConfigId = :configId")
    suspend fun clearWgConfig(configId: Long)

    @Query("UPDATE hosts SET wgConfigId = :configId WHERE id IN (:hostIds)")
    suspend fun setWgConfig(hostIds: List<Long>, configId: Long?)

    @Query("SELECT DISTINCT groupName FROM hosts WHERE groupName != '' ORDER BY groupName COLLATE NOCASE")
    fun observeGroups(): Flow<List<String>>
}

@Dao
interface KeyDao {
    @Query("SELECT * FROM keys ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<KeyEntity>>

    @Query("SELECT * FROM keys WHERE id = :id")
    suspend fun get(id: Long): KeyEntity?

    @Insert
    suspend fun insert(key: KeyEntity): Long

    @Delete
    suspend fun delete(key: KeyEntity)
}

@Dao
interface WgConfigDao {
    @Query("SELECT * FROM wg_configs ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<WgConfigEntity>>

    @Query("SELECT * FROM wg_configs WHERE id = :id")
    suspend fun get(id: Long): WgConfigEntity?

    @Insert
    suspend fun insert(config: WgConfigEntity): Long

    @Update
    suspend fun update(config: WgConfigEntity)

    @Delete
    suspend fun delete(config: WgConfigEntity)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port")
    suspend fun forHost(host: String, port: Int): List<KnownHostEntity>

    @Query("SELECT * FROM known_hosts ORDER BY host, port")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnownHostEntity)

    @Delete
    suspend fun delete(entry: KnownHostEntity)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Insert
    suspend fun insert(snippet: SnippetEntity): Long

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Update
    suspend fun updateAll(snippets: List<SnippetEntity>)

    @Delete
    suspend fun delete(snippet: SnippetEntity)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: Long): TransferEntity?

    @Insert
    suspend fun insert(transfer: TransferEntity): Long

    @Update
    suspend fun update(transfer: TransferEntity)

    @Query("UPDATE transfers SET doneBytes = :done, totalBytes = :total WHERE id = :id")
    suspend fun progress(id: Long, done: Long, total: Long)

    @Query("UPDATE transfers SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: TransferStatus, error: String?)

    /** RP-04: after process death, anything mid-flight is marked failed so the user can retry/resume. */
    @Query("UPDATE transfers SET status = 'FAILED', error = 'Interrupted' WHERE status IN ('RUNNING', 'QUEUED')")
    suspend fun failInterrupted()

    @Query("DELETE FROM transfers WHERE status IN ('DONE', 'CANCELLED')")
    suspend fun clearFinished()
}

class Converters {
    @TypeConverter fun authToString(v: AuthMethod) = v.name
    @TypeConverter fun stringToAuth(v: String) = AuthMethod.valueOf(v)
    @TypeConverter fun formatToString(v: KeyFormat) = v.name
    @TypeConverter fun stringToFormat(v: String) = KeyFormat.valueOf(v)
    @TypeConverter fun dirToString(v: TransferDirection) = v.name
    @TypeConverter fun stringToDir(v: String) = TransferDirection.valueOf(v)
    @TypeConverter fun statusToString(v: TransferStatus) = v.name
    @TypeConverter fun stringToStatus(v: String) = TransferStatus.valueOf(v)
    @TypeConverter fun protocolToString(v: Protocol) = v.name
    @TypeConverter fun stringToProtocol(v: String) = Protocol.valueOf(v)
}

@Database(
    entities = [HostEntity::class, KeyEntity::class, KnownHostEntity::class, SnippetEntity::class, TransferEntity::class, WgConfigEntity::class],
    version = 3,
    exportSchema = true,
    // 2: hosts.protocol (SSH, FTP or FTPS); existing hosts are SSH.
    // 3: wg_configs, and hosts.wgConfigId to bring a WireGuard tunnel up before connecting.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun keys(): KeyDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun snippets(): SnippetDao
    abstract fun transfers(): TransferDao
    abstract fun wgConfigs(): WgConfigDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "shellscout.db").build()
    }
}
