package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.Subtype;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SIM OTA subtypes of a CL-SIM-DD message: what the message asks the SIM to do. One table
 * for the payload (classification) and the app.
 *
 * <p>The subtype comes from the header facts of {@link OtaParser} only. Nothing is decrypted and the secured data is
 * not kept; the S@T and WIB shape checks read the first octets of data that is not ciphered.
 *
 * <p>TAR values (3 octets, ETSI TS 101 220 and Annex D, checked f):
 *
 * <pre>
 * 00 00 00             RAM  issuer security domain, compact format (TS 102 226)
 * B2 01 00             RAM  issuer security domain, expanded format (TS 102 226). Unconfirmed: other RAM TARs
 *                           (security domains other than the ISD) may exist in TS 101 220 Annex D; see the changelog
 *                           open item. Do not change the list without a spec source.
 * B0 00 00 - B0 FF FF  RFM  UICC, SIM and ADF file systems (TS 102 226, TS 31.116); B0 02 00 and up are RFU
 * 50 53 48 'PSH' SAT S@T low priority push (SIMalliance S@T 01.23); the TAR of the Simjacker messages
 * 53 40 54 'S@T'       SAT  S@T browser TAR that SRLabs SIMtester tests with S@T commands
 * 00 00 01 - 00 00 09  WIB  TARs that SRLabs SIMtester tests with WIB commands. The WIB TARs are not in a public
 *                           specification: the operator chooses them (SmartTrust WIB 1.3: pull, push and admin TAR)
 * other                OTHER
 * </pre>
 *
 * <p>The S@T browser and the WIB can have other TARs on some cards. A command packet that is not ciphered and that has
 * the S@T push or WIB command shape ({@link OtaFacts#satPush()}, {@link OtaFacts#wibCommand()}) is SAT or WIB too,
 * unless its TAR is an RFM or RAM TAR.
 *
 * <p>Order: SAT, WIB, NOSEC, RFM and RAM, OTHER. The first code is the subtype and the others are extra
 * subtypes. A header anomaly ({@code AN-OTA-HDR}) gives SIM-OTA-OTHER first, and the TAR and SPI codes that
 * could still be read follow as extras.
 *
 * <p>Thread: any. Never throws.
 */
public final class SimOtaSubtypes {
    static final int TAR_RAM_COMPACT = 0x000000;
    static final int TAR_RAM_EXPANDED = 0xB20100;
    static final int TAR_RFM_FIRST = 0xB00000;
    static final int TAR_RFM_LAST = 0xB0FFFF;
    static final int TAR_SAT_PUSH = 0x505348;
    static final int TAR_SAT = 0x534054;
    static final int TAR_WIB_FIRST = 0x000001;
    static final int TAR_WIB_LAST = 0x000009;

    private static final List<String> OTHER_ONLY = List.of(Subtype.SimOta.OTHER);

    private SimOtaSubtypes() {}

    /**
     * True when {@code f} is a SIM OTA message (CL-SIM-DD): an SMS-DELIVER with PID 0x7F, a message that
     * the framework gives to the UICC ({@link TpduFacts#usimDataDownload()}: class 2 with PID 0x7F or 0x7C), or a
     * class 2 message with a command or response packet header (TS 31.115). The payload classifier calls this.
     */
    public static boolean isSimOta(TpduFacts f) {
        if (f == null || !f.valid() || f.mti() != 0) return false;
        return f.protocolId() == TpduFacts.PID_USIM_DATA_DOWNLOAD
                || f.usimDataDownload()
                || (f.dcsClass() == 2 && f.ota() != null);
    }

    /** The subtype that the TAR alone gives: SAT, WIB, RFM, RAM, or {@code null} for another or an unread TAR. */
    public static String tarSubtype(int tar) {
        if (tar < 0) return null;
        if (tar == TAR_SAT_PUSH || tar == TAR_SAT) return Subtype.SimOta.SAT;
        if (tar >= TAR_WIB_FIRST && tar <= TAR_WIB_LAST) return Subtype.SimOta.WIB;
        if (tar >= TAR_RFM_FIRST && tar <= TAR_RFM_LAST) return Subtype.SimOta.RFM;
        if (tar == TAR_RAM_COMPACT || tar == TAR_RAM_EXPANDED) return Subtype.SimOta.RAM;
        return null;
    }

    /**
     * The SIM OTA codes of {@code f} in order: the subtype first, then the extra subtypes. Never empty. Call it
     * for a message that {@link #isSimOta} accepts. A message with PID 0x7F and no command packet identifier has no
     * header to read and gives SIM-OTA-OTHER.
     */
    public static List<String> codes(TpduFacts f) {
        try {
            return codesUnguarded(f == null ? null : f.ota());
        } catch (RuntimeException e) {
            // Unreachable by design (SimOtaSubtypesTest proves it); fail open.
            return OTHER_ONLY;
        }
    }

    /** As {@link #codes}, for the header facts alone. */
    public static List<String> codes(OtaFacts ota) {
        try {
            return codesUnguarded(ota);
        } catch (RuntimeException e) {
            // Unreachable by design (SimOtaSubtypesTest proves it); fail open.
            return OTHER_ONLY;
        }
    }

    static List<String> codesUnguarded(OtaFacts ota) {
        if (ota == null) return OTHER_ONLY;
        String tarCode = tarSubtype(ota.tar());
        boolean fileOrApplet = Subtype.SimOta.RFM.equals(tarCode) || Subtype.SimOta.RAM.equals(tarCode);
        List<String> out = new ArrayList<>(3);
        if (Anomalies.has(ota.anomalyMask(), AnomalyCode.OTA_HDR)) out.add(Subtype.SimOta.OTHER);
        if (Subtype.SimOta.SAT.equals(tarCode) || (!fileOrApplet && ota.satPush())) out.add(Subtype.SimOta.SAT);
        if (Subtype.SimOta.WIB.equals(tarCode) || (!fileOrApplet && ota.wibCommand())) out.add(Subtype.SimOta.WIB);
        if (ota.noSecurity()) out.add(Subtype.SimOta.NOSEC);
        if (fileOrApplet) out.add(tarCode);
        if (out.isEmpty()) return OTHER_ONLY;
        return Collections.unmodifiableList(out);
    }
}
