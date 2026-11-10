/*
 * Copyright © 2023 Github Lzhiyong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package x.editor.app.database

import android.content.Context

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.PrimaryKey

/** What is known about a download: the ETag the server sent for [url] the last time. */
@Entity(tableName = "headerEntity")
data class DownloadRecord(
    var url: String,
    var etag: String,
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
)

@Dao
public interface DownloadRecordDao {
    // query
    @Query("SELECT * FROM headerEntity")
    fun getAll(): MutableList<DownloadRecord>

    // query with url
    @Query("SELECT * FROM headerEntity WHERE url = :url")
    fun findByUrl(url: String): DownloadRecord?

    // insert a record
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg record: DownloadRecord)

    // insert several records
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(records: MutableList<DownloadRecord>)

    // update a record
    @Update
    fun update(vararg record: DownloadRecord)

    // set every etag to the text null: every download is due again
    @Query("UPDATE headerEntity set etag='null'")
    fun invalidateAllEtags()

    // delete a record
    @Delete
    fun delete(vararg record: DownloadRecord)

    // delete all records
    @Query("DELETE FROM headerEntity")
    fun deleteAll()
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    // constructor
    abstract fun downloadRecordDao(): DownloadRecordDao
}

object DatabaseManager {

    private const val DATABASE_NAME = "database_main"

    @Volatile
    private var database: AppDatabase? = null

    /**
     * The one database of the process. It is opened on the first call and kept:
     * Room expects a single instance per database file, and every further instance
     * would hold its own connections open.
     */
    fun getInstance(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            database ?: buildDatabase(context.applicationContext).also { database = it }
        }
    }

    private fun buildDatabase(context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME).apply {
            allowMainThreadQueries()
            // TODO: when the schema changes, raise the version of AppDatabase and
            // add the migration here: addMigrations(object : Migration(1, 2) { ... })
        }.build()
}
