package com.cardrw.app.di

import com.cardrw.app.data.repository.AidNameRepository
import com.cardrw.app.data.repository.ApduJournalRepository
import com.cardrw.app.data.repository.InMemoryAidNameRepository
import com.cardrw.app.security.EncryptedPrefsSecretStore
import com.cardrw.app.security.SecretStore
import dagger.Binds
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
    fun provideApduJournalRepository(): ApduJournalRepository = ApduJournalRepository()

    @Provides
    @Singleton
    fun provideAidNameRepository(): AidNameRepository = InMemoryAidNameRepository()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecretStore(impl: EncryptedPrefsSecretStore): SecretStore
}
