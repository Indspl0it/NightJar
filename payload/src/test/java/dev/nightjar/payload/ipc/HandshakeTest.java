package dev.nightjar.payload.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.env.Env;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Handshake construction/validation and certificate pinning. */
class HandshakeTest {

    private static final Env ENV = new Env() {
        @Override
        public String buildFingerprint() {
            return "fp";
        }

        @Override
        public String payloadVersion() {
            return "1.0.0";
        }

        @Override
        public String apexVersionsJson() {
            return "{\"a\":\"1\"}";
        }

        @Override
        public int bootCount() {
            return 3;
        }

        @Override
        public boolean userUnlocked() {
            return false;
        }

        @Override
        public String carrierName(int subId) {
            return "";
        }

        @Override
        public int slot(int subId) {
            return 0;
        }

        @Override
        public boolean roaming(int subId) {
            return false;
        }
    };

    @Test
    void IPC_02_helloCarriesEveryDocumentedExtra() {
        Object binder = new Object();
        IpcRequest hello = Handshake.hello(Ipc.authority("dev.nightjar"), ENV, null, binder);
        assertEquals(Ipc.Method.HELLO, hello.method());
        assertEquals("dev.nightjar.ipc", hello.authority());
        assertEquals(Ipc.VERSION, hello.get(Ipc.Key.IPC_VERSION));
        assertEquals("1.0.0", hello.get(Ipc.Key.PAYLOAD_VERSION));
        assertEquals("fp", hello.get(Ipc.Key.BUILD_FINGERPRINT));
        assertEquals("{\"a\":\"1\"}", hello.get(Ipc.Key.APEX_VERSIONS));
        assertEquals(3, hello.get(Ipc.Key.BOOT_COUNT));
        assertEquals(Boolean.FALSE, hello.get(Ipc.Key.USER_UNLOCKED));
        assertEquals(binder, hello.get(Ipc.Key.CONTROL));
        assertFalse(hello.has(Ipc.Key.SESSION_ID));
    }

    @Test
    void IPC_02_replyIsValidated() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(Ipc.Key.IPC_VERSION, 1);
        values.put(Ipc.Key.SESSION_ID, "s");
        values.put(Ipc.Key.RULES, "{}");
        values.put(Ipc.Key.SETTINGS, "{}");
        values.put(Ipc.Key.FIREWALL_ENABLED, true);
        Handshake.Session session = Handshake.parse(new IpcReply(values));
        assertTrue(session.ok());
        assertEquals("s", session.sessionId());
        assertEquals("{}", session.rulesJson());
        assertTrue(session.hasFirewallEnabled());
        assertTrue(session.firewallEnabled());

        values.remove(Ipc.Key.FIREWALL_ENABLED);
        Handshake.Session locked = Handshake.parse(new IpcReply(values));
        assertTrue(locked.ok());
        assertFalse(locked.hasFirewallEnabled(), "no key, no override");
    }

    @Test
    void IPC_02_replyWithoutSessionIsRejected() {
        assertFalse(Handshake.parse(new IpcReply(Map.of(Ipc.Key.IPC_VERSION, 1))).ok());
        assertFalse(Handshake.parse(new IpcReply(Map.of(Ipc.Key.SESSION_ID, "s"))).ok());
        assertFalse(Handshake.parse(null).ok());
        assertFalse(Handshake.parse(IpcReply.empty()).ok());
    }

    @Test
    void IPC_07_appErrorIsPassedThrough() {
        Handshake.Session session = Handshake.parse(new IpcReply(Map.of(Ipc.Key.ERROR, "UPDATE_APP")));
        assertFalse(session.ok());
        assertEquals("UPDATE_APP", session.error());
    }

    @Test
    void IPC_02_oversizeRulesAreDropped() {
        StringBuilder big = new StringBuilder(Ipc.MAX_RULES_BYTES + 1);
        while (big.length() <= Ipc.MAX_RULES_BYTES) big.append('x');
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(Ipc.Key.IPC_VERSION, 1);
        values.put(Ipc.Key.SESSION_ID, "s");
        values.put(Ipc.Key.RULES, big.toString());
        Handshake.Session session = Handshake.parse(new IpcReply(values));
        assertTrue(session.ok());
        assertNull(session.rulesJson());
    }

    @Test
    void SEC_03_pinComparisonIgnoresSeparatorsAndCase() {
        assertEquals("AABB", CertPin.normalize("aa:bb"));
        assertEquals("AABBCC", CertPin.normalize("AA-BB CC"));
        assertNull(CertPin.normalize(""));
        assertNull(CertPin.normalize(null));
    }

    @Test
    void SEC_03_verifiedPinAllowsContentAndMismatchDoesNot() {
        AppSigner signer = pkg -> "AA:BB:CC";
        CertPin pin = new CertPin(signer, null);
        assertTrue(pin.verify("dev.nightjar", "aabbcc"));
        assertFalse(pin.verify("dev.nightjar", "DDEEFF"));
        pin.reset();
        assertFalse(pin.verify("dev.nightjar", null));
        assertFalse(pin.verify(null, "AABBCC"));
    }

    @Test
    void SEC_03_signerFailureWithholdsContent() {
        AppSigner throwing = pkg -> {
            throw new IllegalStateException("no package manager");
        };
        assertFalse(new CertPin(throwing, null).verify("dev.nightjar", "AABB"));
    }
}
