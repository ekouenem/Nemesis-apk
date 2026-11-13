package com.nemesis.apk

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Endpoint parsing tests; no SSH connection or network. */
@RunWith(AndroidJUnit4::class)
class SshEndpointInstrumentedTest {
    @Test fun parsesHostAndDefaults() {
        assertEquals(SshEndpoint("example.org",22,"alice"), SshEndpoint.parse("example.org","22","alice"))
    }
    @Test fun parsesSshUrlAndEmbeddedPortAndUser() {
        assertEquals(SshEndpoint("server.example.org",2222,"bob"),
            SshEndpoint.parse("ssh://bob@server.example.org:2222","22","alice"))
    }
    @Test fun parsesUserAtHostAndHostPort() {
        assertEquals(SshEndpoint("server.example.org",2200,"bob"),
            SshEndpoint.parse("bob@server.example.org:2200","22","alice"))
    }
    @Test fun rejectsNonSshSchemesAndInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) { SshEndpoint.parse("https://example.org","22","alice") }
        assertThrows(IllegalArgumentException::class.java) { SshEndpoint.parse("example.org","70000","alice") }
    }
}
