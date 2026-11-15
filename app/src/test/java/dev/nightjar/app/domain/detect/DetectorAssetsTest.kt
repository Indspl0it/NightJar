package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The shipped DET-05 premium list and DET-02 TAR denylist parse, and every entry has a cited source. */
class DetectorAssetsTest {
    private val premiumJson = File("src/main/assets/premium_numbers.json").readText()
    private val tarJson = File("src/main/assets/sim_tar_denylist.json").readText()
    private val history = FakeDetectionHistory()

    @Test
    fun `the premium asset parses and matches a level 5 short code`() {
        val patterns = PremiumPatterns.parseJson(premiumJson)
        assertFalse(patterns.isEmpty)
        assertTrue(patterns.matches("56767"))
        assertTrue(patterns.matches("5123"))
        // A level 5 fixed-line number (7 or more digits) is not a short code.
        assertFalse(patterns.matches("5551234"))
        assertFalse(patterns.matches("+15559876543210"))
        assertFalse(patterns.matches("12345"))
    }

    @Test
    fun `an outbound message to a level 5 short code gets the premium sentence`() =
        runTest {
            history.seenPackages += "com.example.app"
            val config = DetectorConfig(premium = PremiumPatterns.parseJson(premiumJson))
            val draft =
                ClassDetectors(config, history, KnownOriginatorMatcher.EMPTY)
                    .evaluate(TestEvents.outbound(ClassId.MO_DATA, destination = "56767"), DetectContext.NONE)
                    .single()
            assertTrue(draft.paragraphKeys.contains(TextKey.DET_OUTBOUND_PREMIUM))
        }

    @Test
    fun `the TAR asset parses to the cited S@T Browser and WIB TARs`() {
        assertEquals(setOf(0x505348, 0x534054, 0x000001, 0x000002), TarDenylistJson.parseJson(tarJson))
    }

    @Test
    fun `a message to the S@T Browser TAR gets the denylist sentence`() =
        runTest {
            val config = DetectorConfig(tarDenylist = TarDenylistJson.parseJson(tarJson))
            val facts = SimOtaFacts.parse(spi1 = 0b10, spi2 = 0x00, tar = 0x534054)
            val draft =
                ClassDetectors(config, history, KnownOriginatorMatcher.EMPTY)
                    .evaluate(TestEvents.inbound(ClassId.SIM_DD), DetectContext(simOta = facts))
                    .single()
            assertTrue(draft.paragraphKeys.contains(TextKey.DET_SIM_OTA_TAR))
        }

    @Test
    fun `every listed TAR has an entry with a known source`() {
        val root = JSONObject(tarJson)
        val sourceIds = idsOf(root)
        val tars = root.getJSONArray("tars")
        val entries = root.getJSONArray("entries")
        val documented = (0 until entries.length()).map { entries.getJSONObject(it) }
        assertEquals((0 until tars.length()).map { tars.getString(it) }.toSet(), documented.map { it.getString("tar") }.toSet())
        documented.forEach { assertSourcesKnown(it, sourceIds) }
    }

    @Test
    fun `every premium regex has an entry with a known source`() {
        val root = JSONObject(premiumJson)
        val sourceIds = idsOf(root)
        val regexes = root.getJSONArray("regexes")
        val entries = root.getJSONArray("entries")
        val documented = (0 until entries.length()).map { entries.getJSONObject(it) }
        assertEquals((0 until regexes.length()).map { regexes.getString(it) }.toSet(), documented.map { it.getString("regex") }.toSet())
        assertEquals(0, root.getJSONArray("prefixes").length())
        documented.forEach { assertSourcesKnown(it, sourceIds) }
    }

    @Test
    fun `the TAR parser ignores malformed input`() {
        assertEquals(setOf(0x534054, 0xB00001), TarDenylistJson.parseJson("""{"tars":["0x534054","b00001","1000000","-1","zz",7]}"""))
        assertTrue(TarDenylistJson.parseJson("not json").isEmpty())
        assertTrue(TarDenylistJson.parseJson("""{"other":[]}""").isEmpty())
        assertTrue(TarDenylistJson.parseJson(null).isEmpty())
    }

    private fun idsOf(root: JSONObject): Set<String> {
        val sources = root.getJSONArray("sources")
        return (0 until sources.length())
            .map { sources.getJSONObject(it) }
            .onEach { source ->
                listOf("title", "issuer", "date", "url").forEach { key -> assertTrue(source.optString(key).isNotBlank(), key) }
            }.map { it.getString("id") }
            .toSet()
    }

    private fun assertSourcesKnown(
        entry: JSONObject,
        sourceIds: Set<String>,
    ) {
        val sources = entry.getJSONArray("sources")
        assertTrue(sources.length() > 0)
        (0 until sources.length()).forEach { assertTrue(sources.getString(it) in sourceIds, sources.getString(it)) }
    }
}
