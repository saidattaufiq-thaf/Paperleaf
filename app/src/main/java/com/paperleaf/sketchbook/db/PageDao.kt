package com.paperleaf.sketchbook.db

import androidx.room.*
import com.paperleaf.sketchbook.model.Page
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getPagesForBook(bookId: Long): Flow<List<Page>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: Page): Long

    @Update
    suspend fun update(page: Page)
	
	@Delete
	suspend fun delete(page: Page)

    @Query("DELETE FROM pages WHERE bookId = :bookId")
    suspend fun deleteAllPagesForBook(bookId: Long)

    @Query("SELECT * FROM pages WHERE bookId = :bookId AND pageNumber = :pageNumber LIMIT 1")
    suspend fun getPage(bookId: Long, pageNumber: Int): Page?

    @Query("SELECT COUNT(*) FROM pages WHERE bookId = :bookId")
    suspend fun getPageCount(bookId: Long): Int
}