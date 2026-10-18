package com.stash.core.network.di

import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared client's logger never prints the relays' signature or a supporter's token. */
class RedactedHeadersTest {

    @Test
    fun `the relay signature and the supporter token are redacted with the other secrets`() {
        val redacted = NetworkModule.REDACTED_HEADERS
        for (h in listOf("Authorization", "Cookie", "X-Stash-Edit-Key", "X-Stash-Community-Key", "X-Stash-Auth", "X-Stash-Entitlement")) {
            assertTrue("$h must be redacted", h in redacted)
        }
    }
}
