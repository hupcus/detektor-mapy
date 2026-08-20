package cz.hh.detektormapy.di

import javax.inject.Qualifier

/** The dispatcher every disk / database / zip operation must run on. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

/** The dispatcher for CPU-bound work such as transform fitting or tile compositing. */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class DefaultDispatcher
