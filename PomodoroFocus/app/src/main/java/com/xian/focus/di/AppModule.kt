package com.xian.focus.di

import android.content.Context
import com.xian.focus.data.AppDatabase
import com.xian.focus.data.CountdownDao
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.HabitDao
import com.xian.focus.data.PomodoroRecordDao
import com.xian.focus.data.SubtaskDao
import com.xian.focus.data.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideTaskDao(database: AppDatabase): TaskDao = database.taskDao()

    @Provides
    fun providePomodoroRecordDao(database: AppDatabase): PomodoroRecordDao =
        database.pomodoroRecordDao()

    @Provides
    fun provideSubtaskDao(database: AppDatabase): SubtaskDao = database.subtaskDao()

    @Provides
    fun provideCountdownDao(database: AppDatabase): CountdownDao = database.countdownDao()

    @Provides
    fun provideHabitDao(database: AppDatabase): HabitDao = database.habitDao()

    @Provides
    @Singleton
    fun provideFocusRepository(
        taskDao: TaskDao,
        pomodoroRecordDao: PomodoroRecordDao,
        subtaskDao: SubtaskDao,
        countdownDao: CountdownDao,
        habitDao: HabitDao
    ): FocusRepository = FocusRepository(taskDao, pomodoroRecordDao, subtaskDao, countdownDao, habitDao)
}
