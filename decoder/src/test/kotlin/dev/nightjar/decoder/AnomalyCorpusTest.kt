package dev.nightjar.decoder

import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.Wire
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [Decoders.anomalies] gives the codes of `corpus/anomalies.json` for every single-input fixture. The sequence
 * fixtures need a concatenation tracker; the `:pduparse` tests cover them.
 */
class AnomalyCorpusTest {
    private val file = File("src/test/resources/corpus/anomalies.json")

    @Test
    fun `every single input fixture gives its codes`() {
        val fixtures = JSONObject(file.readText()).getJSONArray("fixtures")
        val failures = mutableListOf<String>()
        var checked = 0
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            if (!fixture.has("hex")) continue
            val codes = fixture.getJSONArray("expect")
            val want = (0 until codes.length()).map { Wire.parse(AnomalyCode::class.java, codes.getString(it), null) }
            val got = Decoders.anomalies(fixture.getString("kind"), fixture.getString("hex"))
            if (got != want) failures += "${fixture.getString("name")}: want $want got $got"
            checked++
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertTrue(checked >= 60, "only $checked fixtures")
    }

    @Test
    fun `kinds without checks and bad hex give no codes`() {
        assertEquals(emptyList<AnomalyCode>(), Decoders.anomalies("cb", "4000"))
        assertEquals(emptyList<AnomalyCode>(), Decoders.anomalies("tpdu", "zz"))
    }
}
