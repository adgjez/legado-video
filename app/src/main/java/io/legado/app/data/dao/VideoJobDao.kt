package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.VideoJob

@Dao
interface VideoJobDao {

    @get:Query("select * from videoJobs order by createdAt desc")
    val all: List<VideoJob>

    @Query("select * from videoJobs order by createdAt desc")
    fun flowAll(): kotlinx.coroutines.flow.Flow<List<VideoJob>>

    @Query("select * from videoJobs where id = :id")
    fun get(id: Long): VideoJob?

    @Query("select * from videoJobs where bookUrl = :bookUrl order by createdAt desc")
    fun getByBook(bookUrl: String): List<VideoJob>

    @Query("select * from videoJobs where status in (0,1,2,3) order by createdAt desc")
    fun getRunning(): List<VideoJob>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg jobs: VideoJob)

    @Update
    fun update(vararg jobs: VideoJob)

    @Delete
    fun delete(vararg jobs: VideoJob)

    @Query("delete from videoJobs where id = :id")
    fun delete(id: Long)
}
