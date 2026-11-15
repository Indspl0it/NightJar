package dev.nightjar.app.domain

import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Transport

/** Builders shared by the JVM tests, so each test states only the field it is about. */
object TestEvents {
    fun inbound(
        classId: ClassId,
        originator: String? = "+15559876543210",
        at: Long = 1_000_000L,
        eventId: String = "e-$at-$classId",
    ): EventFacts =
        EventFacts(
            eventId = eventId,
            direction = Direction.IN,
            transport = Transport.CS,
            source = 0,
            classId = classId,
            fate = Fate.SYSTEM,
            receivedAtWallMs = at,
            originator = originator,
            smsc = "+15559010000500",
            subId = 1,
            slot = 0,
        )

    fun outbound(
        classId: ClassId,
        destination: String = "+15559876543210",
        callingPackage: String? = "com.example.app",
        at: Long = 1_000_000L,
        eventId: String = "o-$at-$classId",
    ): EventFacts =
        EventFacts(
            eventId = eventId,
            direction = Direction.OUT,
            transport = Transport.MO_CS,
            source = -1,
            classId = classId,
            fate = Fate.SENT,
            receivedAtWallMs = at,
            destination = destination,
            callingPackage = callingPackage,
            subId = 1,
            slot = 0,
        )
}
