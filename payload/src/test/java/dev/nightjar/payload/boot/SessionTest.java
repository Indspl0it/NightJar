package dev.nightjar.payload.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Session state machine. */
class SessionTest {

    @Test
    void happyPath() {
        Session s = new Session("id", 1L);
        assertEquals(Session.State.LOADED, s.state());
        assertTrue(s.beginHooking());
        assertEquals(Session.State.HOOKING, s.state());
        assertTrue(s.running(false));
        assertEquals(Session.State.RUNNING, s.state());
        assertEquals(Session.Connection.DISCONNECTED, s.connection());
        assertTrue(s.buffering());
        assertTrue(s.setConnected(true));
        assertTrue(s.connected());
        assertFalse(s.buffering());
        assertTrue(s.setConnected(false));
        assertFalse(s.connected());
    }

    @Test
    void unsupportedFromHooking() {
        Session s = new Session("id", 1L);
        s.beginHooking();
        assertTrue(s.unsupported());
        assertEquals(Session.State.UNSUPPORTED, s.state());
        assertFalse(s.running(true));
    }

    @Test
    void illegalTransitionsAreRefusedNotThrown() {
        Session s = new Session("id", 1L);
        assertFalse(s.running(true), "RUNNING before HOOKING");
        assertNotNull(s.lastRefused());
        assertEquals(Session.State.LOADED, s.state());
        s.beginHooking();
        assertFalse(s.beginHooking());
        assertFalse(s.setConnected(true));
        assertFalse(s.connected());
    }

    @Test
    void unsupportedIsTerminal() {
        Session s = new Session("id", 1L);
        s.beginHooking();
        s.unsupported();
        assertFalse(s.beginHooking());
        assertEquals(Session.State.UNSUPPORTED, s.state());
    }
}
