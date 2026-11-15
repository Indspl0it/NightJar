package dev.nightjar.payload.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The MPSC observation queue and the sealed-event ring. */
class QueueTest {

    @Test
    void fourProducersLoseNothingBelowCapacity() throws Exception {
        EventQueue<Integer> q = new EventQueue<>(4096);
        int threads = 4;
        int perThread = 500;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perThread; i++) q.offer(id * perThread + i);
            });
            th.start();
            ts.add(th);
        }
        start.countDown();
        for (Thread th : ts) th.join();
        int drained = 0;
        while (q.poll() != null) drained++;
        assertEquals(threads * perThread, drained);
        assertEquals(0, q.dropped());
        assertTrue(q.isEmpty());
    }

    @Test
    void overflowDropsAndCounts() {
        EventQueue<Integer> q = new EventQueue<>(2);
        assertTrue(q.offer(1));
        assertTrue(q.offer(2));
        assertFalse(q.offer(3));
        assertEquals(1, q.dropped());
        assertEquals(2, q.size());
        assertEquals(1, q.poll());
    }

    @Test
    void concurrentOverflowNeverExceedsCapacity() throws Exception {
        EventQueue<Integer> q = new EventQueue<>(16);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 100; i++) if (q.offer(i)) accepted.incrementAndGet();
            });
            th.start();
            ts.add(th);
        }
        for (Thread th : ts) th.join();
        assertEquals(16, accepted.get());
        assertEquals(400 - 16, q.dropped());
        assertEquals(16, q.size());
    }

    @Test
    void ringDropsOldestAtEventCap() {
        RingBuffer ring = new RingBuffer(3, 1024 * 1024);
        ring.add(event("a", 1, 10));
        ring.add(event("b", 1, 10));
        ring.add(event("c", 1, 10));
        ring.add(event("d", 1, 10));
        assertEquals(3, ring.pendingCount());
        assertEquals(1, ring.droppedCount());
        assertEquals("b", ring.peek(3).get(0).eventId());
    }

    @Test
    void ringDropsOldestAtByteCap() {
        RingBuffer ring = new RingBuffer(1000, 1200);
        ring.add(event("a", 1, 100)); // 612
        ring.add(event("b", 1, 100)); // 1224 > 1200 → drop a
        assertEquals(1, ring.pendingCount());
        assertEquals("b", ring.peek(10).get(0).eventId());
        assertEquals(1, ring.droppedCount());
    }

    @Test
    void ackRemovesOnlyTheAcknowledgedRevision() {
        RingBuffer ring = new RingBuffer();
        ring.add(event("a", 1, 10));
        ring.add(event("a", 2, 10));
        ring.add(event("b", 1, 10));
        assertTrue(ring.remove("a", 1));
        assertEquals(2, ring.pendingCount());
        assertEquals(2, ring.peek(1).get(0).revision());
        assertFalse(ring.remove("a", 1));
        assertTrue(ring.remove("a", 2));
        assertEquals(1, ring.pendingCount());
    }

    @Test
    void peekKeepsInsertionOrderForResend() {
        RingBuffer ring = new RingBuffer();
        for (int i = 0; i < 5; i++) ring.add(event("e" + i, 1, 10));
        List<OutboundEvent> out = ring.peek(3);
        assertEquals(List.of("e0", "e1", "e2"), out.stream().map(OutboundEvent::eventId).toList());
        assertNull(ring.peek(0).isEmpty() ? null : ring.peek(0).get(0));
    }

    private static OutboundEvent event(String id, int revision, int bytes) {
        return new OutboundEvent(id, revision, "{}", bytes);
    }
}
