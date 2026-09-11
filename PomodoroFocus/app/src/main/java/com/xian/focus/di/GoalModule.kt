package com.xian.focus.di

import android.content.Context
import com.xian.focus.data.DailyGoalPreferences
import com.xian.focus.data.TimerSettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GoalModule {
    @Provides
    @Singleton
    fun provideDailyGoalPreferences(@ApplicationContext context: Context) =
        DailyGoalPreferences(context)

    @Provides
    @Singleton
    fun provideTimerSettingsPreferences(@ApplicationContext context: Context) =
        TimerSettingsPreferences(context)
}
