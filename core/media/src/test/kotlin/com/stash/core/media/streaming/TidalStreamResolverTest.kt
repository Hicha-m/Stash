package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.bitchord.BitchordClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalStreamResolverTest {

    private val client: BitchordClient = mockk {
        coEvery { isConfigured() } returns true
        coEvery { resolve(any(), any()) } returns null
    }

    @Test
    fun `the Tidal AAC switch decides whether streaming asks for AAC`() = runTest {
        for (on in listOf(true, false)) {
            val prefs: LosslessSourcePreferences = mockk { coEvery { tidalAacFallbackNow() } returns on }
            TidalStreamResolver(client, prefs).resolve(TrackEntity(title = "Get Lucky", artist = "Daft Punk"))
            coVerify { client.resolve(any(), allowAac = on) }
        }
    }
}
