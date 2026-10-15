package com.stash.data.download.lossless.tidal.di

import com.stash.data.download.lossless.LosslessSource
import com.stash.data.download.lossless.tidal.TidalLosslessSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers [TidalLosslessSource] in the `Set<LosslessSource>` multibinding.
 * Unconditional, like qbdlx: the source self-gates on whether a relay is configured.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TidalLosslessModule {
    @Binds
    @IntoSet
    abstract fun bindTidalAsLosslessSource(impl: TidalLosslessSource): LosslessSource
}
