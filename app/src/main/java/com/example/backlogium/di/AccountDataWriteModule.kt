package com.example.backlogium.di

import com.example.backlogium.data.repo.AccountDataWriteGuard
import com.example.backlogium.data.repo.RoomAccountDataWriteGuard
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AccountDataWriteModule {
    @Binds
    abstract fun bindAccountDataWriteGuard(guard: RoomAccountDataWriteGuard): AccountDataWriteGuard
}
