package cz.hh.detektormapy.di

import cz.hh.detektormapy.map.LocalTileServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Application-lifetime coroutine scope. Used for work that must outlive a screen -- loading
 * the layer catalogue, persisting preferences, flushing track points.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * One tile server per process. It is started lazily by `LayerManager.ensureStarted()`
     * so that a cold start without the map screen never opens a socket.
     */
    @Provides
    @Singleton
    fun provideLocalTileServer(): LocalTileServer = LocalTileServer()
}
