package dev.nightjar.app.feature.detail

import dev.nightjar.app.R
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every anomaly code has its own plain-words name, and the Evidence card rows follow the severity floor. */
class AnomalyNamesTest {
    @Test
    fun `every anomaly code has its own name`() {
        val ids = AnomalyCode.entries.map { anomalyNameRes(it) }
        assertEquals(AnomalyCode.entries.size, ids.toSet().size)
        assertTrue(R.string.anomaly_name_unknown !in ids)
    }

    @Test
    fun `a wire code gives the same name as its enum constant`() {
        AnomalyCode.entries.forEach { assertEquals(anomalyNameRes(it), anomalyNameRes(it.wire())) }
    }

    @Test
    fun `a code that this app does not know gets the unknown name`() {
        assertEquals(R.string.anomaly_name_unknown, anomalyNameRes("AN-FUTURE"))
    }

    @Test
    fun `rows keep parser order, show a repeated code once and start with a capital letter`() {
        val codes = listOf(AnomalyCode.PORT_ZERO.wire(), AnomalyCode.IE_DUP.wire(), AnomalyCode.PORT_ZERO.wire())
        val names = mapOf(R.string.anomaly_name_port_zero to "port 0", R.string.anomaly_name_ie_dup to "repeated port element")
        val rows = anomalyRows(codes, Severity.INFO) { id -> names.getValue(id) }
        assertEquals(listOf("Port 0", "Repeated port element"), rows.map { it.text })
        assertEquals(listOf(AnomalyCode.PORT_ZERO.wire(), AnomalyCode.IE_DUP.wire()), rows.map { it.code })
    }

    @Test
    fun `an anomaly row is at least Suspicious and follows a higher message severity`() {
        val code = listOf(AnomalyCode.MTI.wire())
        assertEquals(Severity.SUSPICIOUS, anomalyRows(code, Severity.NOTABLE) { "x" }.single().severity)
        assertEquals(Severity.HIGH, anomalyRows(code, Severity.HIGH) { "x" }.single().severity)
    }

    @Test
    fun `no codes give no rows`() {
        assertTrue(anomalyRows(emptyList(), Severity.HIGH) { "x" }.isEmpty())
    }
}
