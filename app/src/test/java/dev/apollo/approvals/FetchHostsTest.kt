package dev.apollo.approvals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FetchHostsTest {
    @Test fun onlyPlainHostNames() {
        assertEquals("api.jolpi.ca", hostOf(" https://API.jolpi.ca/ergast/f1 "))
        assertEquals("site.api.espn.com", hostOf("site.api.espn.com"))
        assertNull(hostOf("localhost"))
        assertNull(hostOf("api.example.com:8443"))
        assertNull(hostOf("user@evil.com"))
        assertNull(hostOf("10.0.0.1"))
    }
}
