package com.cardrw.app.di

import com.cardrw.app.data.repository.AidNameRepository
import com.cardrw.app.data.repository.ApduJournalRepository
import com.cardrw.app.data.repository.InMemoryAidNameRepository
import com.cardrw.app.security.InMemorySecretStore
import com.cardrw.app.security.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSecretStore(): SecretStore = InMemorySecretStore()

    @Provides
    @Singleton
    fun provideApduJournalRepository(): ApduJournalRepository = ApduJournalRepository()

    @Provides
    @Singleton
    fun provideAidNameRepository(): AidNameRepository = InMemoryAidNameRepository()
}
