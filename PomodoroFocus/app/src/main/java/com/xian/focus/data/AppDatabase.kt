package com.xian.focus.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Database(entities=[Task::class,PomodoroRecord::class,Subtask::class,Countdown::class],version=9,exportSchema=false)
abstract class AppDatabase:RoomDatabase(){ abstract fun taskDao():TaskDao; abstract fun pomodoroRecordDao():PomodoroRecordDao; abstract fun subtaskDao():SubtaskDao; abstract fun countdownDao():CountdownDao
 companion object {
  @Volatile private var instance:AppDatabase?=null
  private val MIGRATION_1_2 = object : Migration(1, 2) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN dueTimeMinutes INTEGER")
   }
  }
  private val MIGRATION_2_3 = object : Migration(2, 3) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN repeatRule TEXT NOT NULL DEFAULT 'none'")
    database.execSQL("CREATE TABLE IF NOT EXISTS `subtasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` INTEGER NOT NULL, `title` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, FOREIGN KEY(`taskId`) REFERENCES `tasks`(`id`) ON DELETE CASCADE)")
    database.execSQL("CREATE INDEX IF NOT EXISTS `index_subtasks_taskId` ON `subtasks` (`taskId`)")
   }
  }
  private val MIGRATION_3_4 = object : Migration(3, 4) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
    database.execSQL("UPDATE tasks SET sortOrder = createdAt WHERE sortOrder = 0")
   }
  }
  private val MIGRATION_4_5 = object : Migration(4, 5) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE tasks ADD COLUMN imageUri TEXT")
   }
  }
  private val MIGRATION_5_6 = object : Migration(5, 6) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("CREATE TABLE IF NOT EXISTS `countdowns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `targetDate` INTEGER NOT NULL, `repeatYearly` INTEGER NOT NULL DEFAULT 0, `color` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, `sortOrder` INTEGER NOT NULL DEFAULT 0)")
   }
  }
  private val MIGRATION_6_7 = object : Migration(6, 7) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE countdowns ADD COLUMN linkedTaskId INTEGER NOT NULL DEFAULT 0")
   }
  }
  private val MIGRATION_7_8 = object : Migration(7, 8) {
   override fun migrate(database: SupportSQLiteDatabase) {
    database.execSQL("ALTER TABLE countdowns ADD COLUMN note TEXT NOT NULL DEFAULT ''''")
   }
  }
  private val MIGRATION_8_9 = object : Migration(8, 9) {
   override fun migrate(database: SupportSQLiteDatabase) {
    // 重复任务改为"模板+每日快照"模型：新增 templateId 关联字段
    database.execSQL("ALTER TABLE tasks ADD COLUMN templateId INTEGER NOT NULL DEFAULT 0")
    // 清理旧版"完成即复制"产生的叠加副本：每个重复任务只保留最早一条作为模板
    database.execSQL("DELETE FROM tasks WHERE repeatRule != 'none' AND id NOT IN (SELECT keep_id FROM (SELECT MIN(id) AS keep_id FROM tasks WHERE repeatRule != 'none' GROUP BY title, repeatRule))")
    // 保留的模板重置为未完成，作为每日重复的源头
    database.execSQL("UPDATE tasks SET isCompleted = 0 WHERE repeatRule != 'none'")
   }
  }
  fun getInstance(context:Context)=instance?: synchronized(this){ instance?:Room.databaseBuilder(context.applicationContext,AppDatabase::class.java,"focuslist.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9).build().also{instance=it} } } }
