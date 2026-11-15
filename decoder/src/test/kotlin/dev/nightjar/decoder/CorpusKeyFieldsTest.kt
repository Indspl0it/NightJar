package dev.nightjar.decoder

import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Key fields of the corpus fixtures, checked against the values that the fixtures encode and the specification
 * clause that defines them. The expected JSON files are snapshots of the decoder, so a decoder bug becomes a
 * golden value; these checks do not depend on the decoder output.
 */
class CorpusKeyFieldsTest {
    private val root = File("src/test/resources/corpus")

    private fun fixture(
        format: String,
        name: String,
    ): Field {
        val dir = File(root, format)
        val kind = File(dir, "$name.kind").readText().trim()
        return Decoders.decode(kind, File(dir, "$name.hex").readText().trim())
    }

    @Test
    fun `tpdu fixtures`() {
        val deliver = fixture("tpdu", "deliver_7bit")
        // TS 24.011 8.2.5.1 (RP address) and TS 23.040 9.1.2.5: swapped BCD with an F filler.
        assertEquals("1234567890", deliver.textValue("smsc"))
        // TS 23.040 9.2.3.1: MTI 00 from the SC is SMS-DELIVER.
        assertEquals("SMS-DELIVER", deliver.textValue("first_octet/mti"))
        // TS 23.040 9.1.2.5: TON 101 is an alphanumeric address in the GSM 7-bit alphabet.
        assertEquals("NIGHTJAR", deliver.textValue("originator"))
        // TS 23.040 9.2.3.11: 26-11-05 12:34:56, zone 22 quarters = +05:30.
        assertEquals("2026-11-05T12:34:56+05:30", deliver.textValue("scts"))
        // TS 23.038 6.1.2.1: GSM 7-bit packed text.
        assertEquals("Hello", deliver.textValue("user_data/text"))

        // TS 23.038 4 (DCS 0x08) and 6.2.3: UCS-2.
        assertEquals("Hello", fixture("tpdu", "deliver_ucs2").textValue("user_data/text"))
        assertEquals("BANK", fixture("tpdu", "deliver_ucs2").textValue("originator"))

        // TS 23.040 9.2.3.24.1: concatenation reference 1, 2 parts, part 1; text after 1 fill bit.
        val multipart = fixture("tpdu", "deliver_multipart_7bit")
        val concat = multipart.child("user_data/udh/information_element")!!
        assertEquals("1", concat.textValue("reference"))
        assertEquals("2", concat.textValue("total"))
        assertEquals("1", concat.textValue("sequence"))
        assertEquals("Hello", multipart.textValue("user_data/text"))

        // TS 23.040 9.2.3.1 and 9.2.3.3: first octet 0x11 is SMS-SUBMIT with a relative VP.
        val submit = fixture("tpdu", "submit_relative_vp")
        assertEquals("SMS-SUBMIT", submit.textValue("first_octet/mti"))
        assertEquals("relative", submit.textValue("first_octet/vpf"))
        assertEquals("1234567890", submit.textValue("destination"))
        // TS 23.040 9.2.3.12.1: 0xAA = 170, (170 - 166) days.
        assertEquals("4 days", submit.textValue("validity_period/relative"))
        assertEquals("Hi", submit.textValue("user_data/text"))

        // TS 23.040 9.2.2.3 and 9.2.3.15: status 0x00 = received by the SME.
        val status = fixture("tpdu", "status_report")
        assertEquals("SMS-STATUS-REPORT", status.textValue("first_octet/mti"))
        assertEquals("1", status.textValue("message_reference"))
        assertEquals("1234567890", status.textValue("recipient"))
        assertEquals("completed: received by the SME", status.textValue("status/meaning"))

        // TS 23.040 9.2.3.24.15/16 and TS 23.038 6.2.1.2.4: language 6 Hindi, 11 Tamil, 4 Bengali.
        assertEquals("Hindi", fixture("tpdu", "deliver_hindi_single_shift").textValue("user_data/udh/information_element/language"))
        assertEquals("Tamil", fixture("tpdu", "deliver_tamil_locking_shift").textValue("user_data/udh/information_element/language"))
        assertEquals("Bengali", fixture("tpdu", "deliver_bengali_locking_shift").textValue("user_data/udh/information_element/language"))

        // TS 24.011 8.2.5.1 and SMSC length 7 covers the full input of 8 octets, so there is no SMSC prefix
        // and the input is a bare TPDU. Its first octet 0x07 has MTI 11, which TS 23.040 9.2.3.1 reserves (DECODER-V01).
        val shortSmsc = fixture("tpdu", "malformed_short_smsc")
        assertNull(shortSmsc.textValue("smsc"))
        assertNotNull(shortSmsc.child("smsc/note"))
        assertEquals("07", shortSmsc.textValue("first_octet"))
        assertEquals("reserved", shortSmsc.textValue("first_octet/mti"))
        assertEquals("The message type indicator 3 is reserved.", shortSmsc.textValue("error"))
    }

    @Test
    fun `udh fixtures`() {
        // TS 23.040 9.2.3.24.1.
        val concat = fixture("udh", "concat_8bit").child("information_element")!!
        assertEquals("concatenated_8bit", concat.textValue("name"))
        assertEquals("2", concat.textValue("total"))
        // TS 23.040 9.2.3.24.4: 16-bit ports, destination 0x0B84 = 2948 (WAP push), originator 0x23F0 = 9200.
        val ports = fixture("udh", "ports_16bit").child("information_element")!!
        assertEquals("2948", ports.textValue("destination_port"))
        assertEquals("9200", ports.textValue("originator_port"))
        // TS 23.040 9.2.3.24.15/16, TS 23.038 6.2.1.2.4.
        assertEquals("Hindi", fixture("udh", "language_hindi_single").textValue("information_element/language"))
        assertEquals("Tamil", fixture("udh", "language_tamil_locking").textValue("information_element/language"))
    }

    @Test
    fun `wsp fixtures`() {
        // WAP-230 8.4.2.24 and the OMNA content type codes: 0x2E sic; OMNA push application id 0x02 wml.ua.
        val si = fixture("wsp", "push_si")
        assertEquals("application/vnd.wap.sic", si.textValue("content_type"))
        assertEquals("x-wap-application:wml.ua", si.textValue("headers/header/value"))
        // WAP-167 (SI): the nested document.
        assertEquals("http://www.xyz.com/email/123/abc.wml", si.textValue("wbxml/document/si/indication/@href"))
        // 0x30 slc.
        assertEquals("application/vnd.wap.slc", fixture("wsp", "push_sl").textValue("content_type"))
        // 0x36 connectivity-wbxml (OMA-WAP-ProvCont): NAP-ADDRESS is the APN.
        val cp = fixture("wsp", "push_oma_cp")
        assertEquals("application/vnd.wap.connectivity-wbxml", cp.textValue("content_type"))
        assertEquals("internet", cp.textValue("oma_cp/summary/apn"))
        // 0x44 syncml.notification, application id 0x07 syncml.dm (OMA DM 1.2 package #0).
        val dm = fixture("wsp", "push_oma_dm")
        assertEquals("application/vnd.syncml.notification", dm.textValue("content_type"))
        assertEquals("x-wap-application:syncml.dm", dm.textValue("headers/header/value"))
        assertEquals("1.2", dm.textValue("oma_dm_notification/trigger_header/version"))
        // 0x3E mms-message, application id 0x04 mms.ua.
        val mms = fixture("wsp", "push_mms")
        assertEquals("application/vnd.wap.mms-message", mms.textValue("content_type"))
        assertEquals("x-wap-application:mms.ua", mms.textValue("headers/header/value"))
        // WAP-230 8.4.2.24 general form with a Long-integer: 0x0312 omaloc-supl-init; OMNA application id 0x10 ulp.ua.
        val supl = fixture("wsp", "push_supl")
        assertEquals("application/vnd.omaloc-supl-init", supl.textValue("content_type"))
        assertEquals("x-oma-application:ulp.ua", supl.textValue("headers/header/value"))
        assertEquals("noNotificationNoVerification", supl.textValue("supl_init/init/notification/notification_type"))
    }

    @Test
    fun `wbxml fixtures`() {
        // WAP-167 (SI) 8.3 and the WBXML attribute tokens of SI.
        val si = fixture("wbxml", "si_example")
        assertEquals("SI 1.0", si.textValue("public_id/name"))
        assertEquals("http://www.xyz.com/email/123/abc.wml", si.textValue("document/si/indication/@href"))
        assertEquals("You have 4 new e-mails", si.textValue("document/si/indication/#text"))
        // WAP-168 (SL).
        assertEquals("http://www.xyz.com/ppaid/123/abc.wml", fixture("wbxml", "sl_example").textValue("document/sl/@href"))
        // WAP-175 (CO).
        val co = fixture("wbxml", "co_example").child("document/co")!!
        assertEquals("foo.wml", co.children[0].textValue("@uri"))
        assertEquals("/bar", co.children[1].textValue("@uri"))
    }

    @Test
    fun `oma cp fixtures`() {
        // OMA-WAP-ProvCont: NAPDEF NAP-ADDRESS (APN), NAME, PXPHYSICAL PXADDR + PORTNBR, STARTPAGE.
        val cp = fixture("oma_cp", "napdef_proxy_startpage")
        assertEquals("internet", cp.textValue("summary/apn"))
        assertEquals("My APN", cp.textValue("summary/name"))
        assertEquals("10.0.0.1:8080", cp.textValue("summary/proxy"))
        assertEquals("http://example.com/", cp.textValue("summary/home_page"))
        // WAP-192 5.8.1 (separate attribute code page) and ProvCont page 1: TYPE=APPLICATION.
        val app = fixture("oma_cp", "application_page1")
        assertEquals("APPLICATION", app.textValue("wbxml/document/wap-provisioningdoc/characteristic/@type"))
        assertEquals("APPID", app.textValue("wbxml/document/wap-provisioningdoc/characteristic/parm/@name"))
    }

    @Test
    fun `oma dm fixtures`() {
        // OMA-TS-DM_Notification-V1_2 section 6 (package #0): digest, version 10 bits, ui-mode, initiator, session id.
        val dm = fixture("oma_dm", "notification")
        assertEquals("0102030405060708090A0B0C0D0E0F10", dm.textValue("digest"))
        assertEquals("1.2", dm.textValue("trigger_header/version"))
        assertEquals("background", dm.textValue("trigger_header/ui_mode"))
        assertEquals("server", dm.textValue("trigger_header/initiator"))
        assertEquals("1234", dm.textValue("trigger_header/session_id"))
        assertEquals("com.example.dm", dm.textValue("trigger_header/server_id"))
        assertEquals("CAFE", dm.textValue("trigger_body"))
    }

    @Test
    fun `supl fixtures`() {
        // OMA-TS-ULP in X.691 UPER: the SUPL vectors V1-V7 that the payload shares, and their results.
        val types =
            mapOf(
                "init_v1_silent" to "noNotificationNoVerification",
                "init_v2_notify_fqdn_mac" to "notificationOnly",
                "init_v3_verify_allowed" to "notificationAndVerficationAllowedNA",
                "init_v4_verify_denied_no_position" to "notificationAndVerficationDeniedNA",
                "init_v5_privacy_override" to "privacyOverride",
            )
        for ((name, type) in types) {
            assertEquals(type, fixture("supl", name).textValue("init/notification/notification_type"), name)
        }
        val v2 = fixture("supl", "init_v2_notify_fqdn_mac")
        assertEquals("198.51.100.7", v2.textValue("session_id/set_session_id/set_id"))
        assertEquals("example.org", v2.textValue("init/slp_address"))
        assertEquals("0102030405060708", v2.textValue("init/mac"))
        assertEquals("noPosition", fixture("supl", "init_v4_verify_denied_no_position").textValue("init/pos_method"))
        assertNull(fixture("supl", "init_v6_no_notification").child("init/notification"))
        assertEquals("msSUPLEND", fixture("supl", "end_v7_not_init").textValue("message"))
    }

    @Test
    fun `sms pp fixtures`() {
        // TS 31.115 / TS 102 225 5.1: SPI 00 00, TAR B00010 (RFM, TS 31.116), CNTR 1.
        val none = fixture("sms_pp", "spi_none")
        assertEquals("none", none.textValue("spi/rc_cc_ds"))
        assertEquals("B00010", none.textValue("tar"))
        assertEquals(Subtype.SimOta.RFM, none.textValue("tar/subtype"))
        assertEquals("1", none.textValue("cntr/value"))
        // TS 102 225 5.1.1: SPI1 0x02 cryptographic checksum; 5.1.3: KID 0x02 with CC = AES (CMAC).
        val cc = fixture("sms_pp", "spi_cc")
        assertEquals("cryptographic-checksum", cc.textValue("spi/rc_cc_ds"))
        assertEquals("AES-CMAC", cc.textValue("kid/algorithm"))
        assertEquals("1122334455667788", cc.textValue("rc_cc_ds"))
        // TS 102 225 5.1.1: SPI1 0x07 = digital signature with ciphering, so the counter is ciphertext.
        val ds = fixture("sms_pp", "spi_ds_ciphered")
        assertEquals("digital-signature", ds.textValue("spi/rc_cc_ds"))
        assertEquals("true", ds.textValue("spi/ciphering"))
        assertEquals("true", ds.textValue("cntr/ciphered"))
        // TS 102 225 5.1.1: SPI2 0x09 = PoR required with a cryptographic checksum.
        val por = fixture("sms_pp", "spi_por_cc")
        assertEquals("required", por.textValue("spi/por"))
        assertEquals("cryptographic-checksum", por.textValue("spi/por_security"))
        // TS 102 225 5.2: response status 0x00 = PoR OK.
        val response = fixture("sms_pp", "response_ok")
        assertEquals("PoR OK", response.textValue("status/meaning"))
        assertEquals("78797A", response.textValue("additional_data"))
    }

    @Test
    fun `cell broadcast fixtures`() {
        // TS 23.041 9.4.1.2.2: 4352 ETWS earthquake (a GSM page of 88 octets, not a primary notification).
        val etws = fixture("cb", "gsm_etws_secondary")
        assertEquals("GSM", etws.value)
        assertEquals("ETWS earthquake warning", etws.textValue("message_identifier/channel"))
        assertEquals("true", etws.textValue("message_identifier/emergency"))
        assertEquals("Earthquake alert", etws.textValue("content/text"))
        assertEquals("CMAS Presidential / EU-Alert Level 1", fixture("cb", "gsm_cmas_presidential").textValue("message_identifier/channel"))
        // TS 23.041 9.4.1.2.4: page parameter 0x23 = page 2 of 3.
        assertEquals("2 of 3", fixture("cb", "gsm_multipage_2of3").textValue("page_parameter"))
        // TS 23.041 9.4.2.2: UMTS, 1 page, length octet 82.
        val umts = fixture("cb", "umts_cmas")
        assertEquals("1", umts.textValue("number_of_pages"))
        assertEquals("Presidential alert", umts.textValue("content/text"))
        // TS 23.041 9.4.1.3: ETWS primary, warning type 0 earthquake with alert and popup, time stamp +02:00.
        val primary = fixture("cb", "etws_primary")
        assertEquals("ETWS-primary", primary.value)
        assertEquals("earthquake", primary.textValue("warning_type"))
        assertEquals("true", primary.textValue("warning_type/emergency_user_alert"))
        assertEquals("2026-11-05T12:34:56+02:00", primary.textValue("warning_security_information/timestamp"))
        // TS 23.038 clause 5: coding group 1010 is reserved and read as GSM 7-bit; identifier 50 = area information.
        val reserved = fixture("cb", "reserved_dcs")
        assertEquals("true", reserved.textValue("dcs/reserved"))
        assertEquals("Area", reserved.textValue("content/text"))
        assertNotNull(reserved.child("message_identifier/subtype"))
    }
}
