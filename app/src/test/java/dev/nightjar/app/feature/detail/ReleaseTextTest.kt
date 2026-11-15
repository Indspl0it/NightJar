package dev.nightjar.app.feature.detail

import dev.nightjar.app.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** APPDATA-X03: a release failure code never reaches the user as text; each known code has its own message. */
class ReleaseTextTest {
    @Test
    fun `known codes map to their own text`() {
        assertEquals(R.string.release_not_active, releaseFailureRes("RELEASE_NOT_ACTIVE"))
        assertEquals(R.string.release_no_pdu, releaseFailureRes("RELEASE_NO_PDU"))
        assertEquals(R.string.release_timeout, releaseFailureRes("RELEASE_TIMEOUT"))
    }

    @Test
    fun `an unknown code gets the general text`() {
        assertEquals(R.string.release_failed, releaseFailureRes("RELEASE_OUT_OF_MEMORY"))
        assertEquals(R.string.release_failed, releaseFailureRes(""))
    }
}
