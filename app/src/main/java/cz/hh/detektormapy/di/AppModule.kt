package cz.hh.detektormapy.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** App-wide primitives: JSON codec and coroutine dispatchers. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Shared JSON codec.
     *
     * `ignoreUnknownKeys` keeps a `layers.json` or an export written by a newer build readable,
     * `prettyPrint` keeps the files the user edits by hand on a desktop diffable.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
