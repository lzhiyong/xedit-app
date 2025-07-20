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
 
package x.code.app.model

import android.content.Context

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Ignore
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "headerEntity")
data class HeaderEntity(
    var url: String,
    var etag: String,
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
)

@Dao
public interface EntityDao {
    // query
    @Query("SELECT * FROM headerEntity")
    fun queryAll(): MutableList<HeaderEntity>

    // query with url
    @Query("SELECT * FROM headerEntity WHERE url = :url")
    fun query(url: String): HeaderEntity?

    // insert a data
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(vararg entity: HeaderEntity)

    // insert multiple datas
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addAll(list: MutableList<HeaderEntity>)

    // update a data
    @Update
    fun update(vararg entity: HeaderEntity)

    // update all datas
    @Query("UPDATE headerEntity set etag='null'")
    fun updateAll()

    // dalete a data
    @Delete
    fun delete(vararg entity: HeaderEntity)

    // delete all datas
    @Query("DELETE FROM headerEntity")
    fun deleteAll()
}

@Database(entities = [HeaderEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    // constructor
    abstract fun entityDao(): EntityDao
}

object DatabaseManager {

    private val LOG_TAG = DatabaseManager::class.simpleName
    
    private lateinit var database: AppDatabase
    
    fun getInstance(context: Context): AppDatabase {
        return if(!::database.isInitialized) {
            Room.databaseBuilder(context, AppDatabase::class.java, "database_main").apply {
                allowMainThreadQueries()
                addCallback(object: RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // TODO         
                    }
                })
                addMigrations(object: Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        // TODO                     
                    }
                })
            }.build()
        } else {
            // return the database
            database
        }
    }
}

