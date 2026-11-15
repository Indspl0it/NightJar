package dev.nightjar.app.domain.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** Every release code gets a sentence, never the code itself. */
class ReleaseFailureTextTest {
    @Test
    fun `each known code has its own sentence`() {
        assertEquals(TextKey.RELEASE_TIMEOUT, ReleaseFailureText.keyOf("RELEASE_TIMEOUT"))
        assertEquals(TextKey.RELEASE_REJECTED, ReleaseFailureText.keyOf("RELEASE_CALLER_REJECTED"))
        assertEquals(TextKey.RELEASE_NOT_ACTIVE, ReleaseFailureText.keyOf("RELEASE_NOT_ACTIVE"))
    }

    @Test
    fun `an other code gets the general sentence`() {
        assertEquals(TextKey.RELEASE_FAILED, ReleaseFailureText.keyOf("RELEASE_OUT_OF_MEMORY"))
        assertEquals(TextKey.RELEASE_FAILED, ReleaseFailureText.keyOf(null))
    }

    @Test
    fun `no sentence shows a code`() {
        val strings = MapStrings()
        for (code in listOf("RELEASE_TIMEOUT", "RELEASE_GENERIC_FAILURE", "RELEASE_UNKNOWN_ERROR")) {
            assertFalse(strings.get(ReleaseFailureText.keyOf(code)).contains("RELEASE_"), code)
        }
    }
}
