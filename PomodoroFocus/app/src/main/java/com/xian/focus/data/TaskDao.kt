package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 任务表。
 *
 * **`deletedAt = 0` 是所有正常查询的硬前提** —— 删除已经改成"打时间戳进回收站"，
 * 漏掉这个条件的读查询会把已删任务重新放回列表/统计/提醒里。新增读方法时务必带上。
 * 只有 `getTrashedTasks` 这类回收站专用查询才反过来取 `deletedAt > 0`。
 */
@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE listType=:listType AND deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getTasksByListType(listType: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=0 AND deletedAt = 0 ORDER BY (dueDate IS NULL), dueDate ASC, (dueTimeMinutes IS NULL), dueTimeMinutes ASC, priority ASC, createdAt DESC")
    suspend fun getPendingTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=0 AND listType=:listType AND deletedAt = 0 ORDER BY (dueDate IS NULL), dueDate ASC, (dueTimeMinutes IS NULL), dueTimeMinutes ASC, priority ASC, createdAt DESC")
    suspend fun getPendingTasksByListType(listType: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=1 AND deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getCompletedTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=1 AND listType=:listType AND deletedAt = 0 ORDER BY createdAt DESC")
    suspend fun getCompletedTasksByListType(listType: String): List<Task>

    /** 分类下拉的数据源。已删任务不能贡献分类，否则删干净之后分类还挂在筛选里。 */
    @Query("SELECT DISTINCT listType FROM tasks WHERE deletedAt = 0 ORDER BY listType ASC")
    suspend fun getListTypes(): List<String>

    /**
     * 单个任务。同样排除已删的 —— 计时器、倒数日关联任务都拿它取数据，
     * 返回已删任务等于让它们继续操作一个"不存在"的行。
     */
    @Query("SELECT * FROM tasks WHERE id=:id AND deletedAt = 0 LIMIT 1")
    suspend fun getTaskById(id: Int): Task?

    @Query("SELECT * FROM tasks WHERE templateId=:templateId AND dueDate=:dueDate AND isCompleted=1 AND deletedAt = 0 LIMIT 1")
    suspend fun getCompletedSnapshot(templateId: Int, dueDate: Long): Task?

    /**
     * 某模板在某天的**任意**快照（含未完成的）。
     *
     * 用于「移到今天」：重复任务的实例是模板虚拟带出的，要让它真的落到某一天，
     * 唯一做法是写一条那天的快照 —— 而写之前必须先看看那天有没有（避免重复建）。
     * 与 [getCompletedSnapshot] 的区别只在于不限定 `isCompleted`。
     */
    @Query("SELECT * FROM tasks WHERE templateId=:templateId AND dueDate=:dueDate AND deletedAt = 0 LIMIT 1")
    suspend fun getSnapshotOn(templateId: Int, dueDate: Long): Task?

    @Query("DELETE FROM tasks WHERE templateId=:templateId AND dueDate=:dueDate AND isCompleted=1 AND deletedAt = 0")
    suspend fun deleteCompletedSnapshot(templateId: Int, dueDate: Long)

    @Insert
    suspend fun insertTask(task: Task): Long

    /**
     * CSV 恢复专用：按原 id 批量写回。
     * 用 IGNORE 而不是默认的 ABORT —— 把备份导进一台已有数据的手机时，
     * 撞 id 的行应当跳过，而不是让整次导入崩掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasksFromBackup(tasks: List<Task>)

    @Update
    suspend fun updateTask(task: Task)

    @Update
    suspend fun updateTasks(tasks: List<Task>)

    // ------------------------------------------------------------------ 回收站

    /**
     * 删单条 = 打删除戳。
     * 子任务不跟着删 —— 它的外键是 ON DELETE CASCADE，真删时才会连带，
     * 而回收站里还原的正是"连子任务一起回来"，所以这一层留给彻底删除去触发。
     */
    @Query("UPDATE tasks SET deletedAt=:deletedAt WHERE id=:id AND deletedAt = 0")
    suspend fun trashTask(id: Int, deletedAt: Long)

    /** 「删除全部该事件」：模板行连同它名下所有快照行一起进回收站。 */
    @Query("UPDATE tasks SET deletedAt=:deletedAt WHERE deletedAt = 0 AND (id=:templateId OR templateId=:templateId)")
    suspend fun trashTemplateWithSnapshots(templateId: Int, deletedAt: Long)

    /**
     * 「删除全部该事件（保留已完成的）」第一步：把已完成的快照摘出来变成普通任务。
     *
     * 必须连 repeatRule 一起清掉 —— 只把 templateId 置 0 的话，
     * `repeatRule != none && templateId == 0` 恰好就是「重复任务模板」的判定条件，
     * 这些本该安静留在历史里的记录会被当成模板，从今天起每天重新冒出来。
     */
    @Query("UPDATE tasks SET templateId=0, repeatRule='none' WHERE templateId=:templateId AND isCompleted=1 AND deletedAt = 0")
    suspend fun detachCompletedSnapshots(templateId: Int)

    /** 上一步之后的第二步：把模板本身、以及仍属该系列的未完成快照，一起放进回收站。 */
    @Query("UPDATE tasks SET deletedAt=:deletedAt WHERE deletedAt = 0 AND (id=:templateId OR (templateId=:templateId AND isCompleted=0))")
    suspend fun trashTemplateAndUnfinishedSnapshots(templateId: Int, deletedAt: Long)

    /**
     * 回收站列表。按「重复系列」聚合由上层做（同一系列的模板与快照要合起来还原），
     * 这里只负责把行取全。
     */
    @Query("SELECT * FROM tasks WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun getTrashedTasks(): List<Task>

    /** 还原整个系列：[seriesId] 是模板 id（单条任务就是它自己的 id）。 */
    @Query("UPDATE tasks SET deletedAt=0 WHERE id=:seriesId OR templateId=:seriesId")
    suspend fun restoreTaskSeries(seriesId: Int)

    /** 彻底删除整个系列。子任务由外键 ON DELETE CASCADE 连带清掉。 */
    @Query("DELETE FROM tasks WHERE id=:seriesId OR templateId=:seriesId")
    suspend fun purgeTaskSeries(seriesId: Int)

    /** 回收站里超过保留期的行，到期真删。 */
    @Query("DELETE FROM tasks WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeExpiredTasks(cutoff: Long)

    /** 清空回收站。 */
    @Query("DELETE FROM tasks WHERE deletedAt > 0")
    suspend fun purgeAllTrashedTasks()
}
