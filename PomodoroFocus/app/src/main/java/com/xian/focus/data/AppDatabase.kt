package com.xian.focus.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
@Database(entities=[Task::class,PomodoroRecord::class,Subtask::class,Countdown::class,Habit::class,HabitLog::class],version=12,exportSchema=false)
abstract class AppDatabase:RoomDatabase(){ abstract fun taskDao():TaskDao; abstract fun pomodoroRecordDao():PomodoroRecordDao; abstract fun subtaskDao():SubtaskDao; abstract fun countdownDao():CountdownDao; abstract fun habitDao():HabitDao
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
  private val MIGRATION_9_10 = object : Migration(9, 10) {
   override fun migrate(database: SupportSQLiteDatabase) {
    // 8→9 那次清理只在版本升级时跑过一次，管不了之后写进来的值：
    // 把一个「已完成的普通任务」在编辑对话框里改成每日/每周/每月时，
    // task.copy(repeatRule = ...) 会把 isCompleted = true 一起带进模板，
    // 于是后面每一天都显示成已完成、周条 7 天全满，而且取消也取消不掉。
    // 这里把存量脏值抹平；新写入的值由 FocusRepository 的归一化兜住。
    database.execSQL("UPDATE tasks SET isCompleted = 0 WHERE templateId = 0 AND repeatRule != 'none'")
   }
  }
  private val MIGRATION_10_11 = object : Migration(10, 11) {
   override fun migrate(database: SupportSQLiteDatabase) {
    // 回收站：删除不再真删，改在行上打一个时间戳。0 = 未删除（存量数据全部是 0）。
    // 带默认值，所以老用户的既有任务不会因为这次升级而消失或需要重填。
    database.execSQL("ALTER TABLE tasks ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
    database.execSQL("ALTER TABLE countdowns ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
   }
  }
  private val MIGRATION_11_12 = object : Migration(11, 12) {
   override fun migrate(database: SupportSQLiteDatabase) {
    // 小习惯打卡：两张新表（习惯 + 打卡日志）。纯新增，不动任何既有表，
    // 所以老用户升级上来任务/倒数日/回收站里的东西一个都不会少。
    database.execSQL("CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `freqType` TEXT NOT NULL, `weeklyTarget` INTEGER NOT NULL, `weekDaysMask` INTEGER NOT NULL, `targetPerDay` INTEGER NOT NULL, `startDate` INTEGER NOT NULL, `remindMinutes` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
    // 打卡日志一天一行（count 攒次数），(habitId, day) 唯一 —— 重复打卡在数据库层就挡住。
    database.execSQL("CREATE TABLE IF NOT EXISTS `habit_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `count` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_logs_habitId_day` ON `habit_logs` (`habitId`, `day`)")
   }
  }
  fun getInstance(context:Context)=instance?: synchronized(this){ instance?:Room.databaseBuilder(context.applicationContext,AppDatabase::class.java,"focuslist.db").addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10,MIGRATION_10_11,MIGRATION_11_12).build().also{instance=it} } } }
