package com.tuptup.speech.data

import android.content.Context
import androidx.room.*

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val name: String,
    val points: String
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY id")
    suspend fun all(): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: Product)

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Product::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                ctx.applicationContext,
                AppDatabase::class.java,
                "tuptup_speech.db"
            ).build().also { INSTANCE = it }
        }
    }
}
