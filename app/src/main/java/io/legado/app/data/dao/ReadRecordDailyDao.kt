package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadRecordDaily

@Dao
interface ReadRecordDailyDao {

    @get:Query("select * from readRecordDaily order by date desc")
    val allDesc: List<ReadRecordDaily>

    @get:Query("select count(*) from readRecordDaily")
    val count: Int

    @Query("select * from readRecordDaily where date = :date limit 1")
    fun get(date: String): ReadRecordDaily?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg record: ReadRecordDaily)

    @Query("delete from readRecordDaily")
    fun clear()

    // 热力图、今日、本月、活跃天 必需查询
    @Query("select * from readRecordDaily where date between :startDate and :endDate order by date asc")
    fun getBetween(startDate: String, endDate: String): List<ReadRecordDaily>

    @Query("select sum(readTime) from readRecordDaily where date like :month || '%'")
    fun getMonthReadTime(month: String): Long?

    @Query("select count(*) from readRecordDaily where readTime > 0")
    fun getActiveDayCount(): Int
}