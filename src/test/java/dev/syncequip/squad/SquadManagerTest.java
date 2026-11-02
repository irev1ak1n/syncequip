package dev.syncequip.squad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.squad.SquadManager.Status;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SquadManagerTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private SquadManager squads;
    private final UUID leader = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        squads = new SquadManager(clock::get);
    }

    @Test
    void createInviteJoin() {
        assertEquals(Status.OK, squads.create(leader));
        assertEquals(Status.OK, squads.invite(leader, alice, 30, 60_000));
        assertEquals(Status.OK, squads.join(alice, leader, 30));

        Squad squad = squads.squadOf(alice).orElseThrow();
        assertEquals(squad, squads.squadOf(leader).orElseThrow());
        assertEquals(List.of(leader, alice), List.copyOf(squad.members()));
        assertTrue(squad.isLeader(leader));
    }

    @Test
    void cannotCreateTwice() {
        squads.create(leader);
        assertEquals(Status.ALREADY_IN_SQUAD, squads.create(leader));
    }

    @Test
    void onlyLeaderCanInvite() {
        squads.create(leader);
        squads.invite(leader, alice, 30, 60_000);
        squads.join(alice, leader, 30);

        assertEquals(Status.NOT_LEADER, squads.invite(alice, bob, 30, 60_000));
        assertEquals(Status.NOT_IN_SQUAD, squads.invite(bob, alice, 30, 60_000));
        assertEquals(Status.SELF, squads.invite(leader, leader, 30, 60_000));
        assertEquals(Status.TARGET_ALREADY_MEMBER, squads.invite(leader, alice, 30, 60_000));
    }

    @Test
    void joiningRequiresAValidInvite() {
        squads.create(leader);
        assertEquals(Status.NOT_INVITED, squads.join(alice, leader, 30));
        assertEquals(Status.NO_SUCH_SQUAD, squads.join(alice, bob, 30));

        squads.invite(leader, alice, 30, 60_000);
        clock.addAndGet(60_001);
        assertEquals(Status.NOT_INVITED, squads.join(alice, leader, 30), "invite expired");
    }

    @Test
    void maxSquadSizeIsEnforced() {
        squads.create(leader);
        squads.invite(leader, alice, 2, 60_000);
        squads.invite(leader, bob, 2, 60_000);
        assertEquals(Status.OK, squads.join(alice, leader, 2));
        assertEquals(Status.SQUAD_FULL, squads.join(bob, leader, 2));
        assertEquals(Status.SQUAD_FULL, squads.invite(leader, UUID.randomUUID(), 2, 60_000));
    }

    @Test
    void invitingMemberOfAnotherSquadIsReported() {
        squads.create(leader);
        squads.create(alice);
        assertEquals(Status.TARGET_IN_OTHER_SQUAD, squads.invite(leader, alice, 30, 60_000));
        assertEquals(Status.ALREADY_IN_SQUAD, squads.join(alice, leader, 30));
    }

    @Test
    void leaderLeavingTransfersLeadershipToOldestMember() {
        squads.create(leader);
        squads.invite(leader, alice, 30, 60_000);
        squads.invite(leader, bob, 30, 60_000);
        squads.join(alice, leader, 30);
        squads.join(bob, leader, 30);

        SquadManager.LeaveResult result = squads.leave(leader);
        assertEquals(Status.OK, result.status());
        assertEquals(alice, result.newLeader());
        assertFalse(result.disbanded());
        assertTrue(squads.squadOf(bob).orElseThrow().isLeader(alice));
        assertTrue(squads.squadOf(leader).isEmpty());
    }

    @Test
    void lastMemberLeavingDisbands() {
        squads.create(leader);
        SquadManager.LeaveResult result = squads.leave(leader);
        assertTrue(result.disbanded());
        assertNull(result.newLeader());
        assertTrue(squads.squads().isEmpty());
        assertEquals(Status.NOT_IN_SQUAD, squads.leave(leader).status());
    }

    @Test
    void memberLeavingKeepsLeader() {
        squads.create(leader);
        squads.invite(leader, alice, 30, 60_000);
        squads.join(alice, leader, 30);
        SquadManager.LeaveResult result = squads.leave(alice);
        assertNull(result.newLeader());
        assertEquals(1, squads.squadOf(leader).orElseThrow().size());
    }

    @Test
    void transferLeadership() {
        squads.create(leader);
        squads.invite(leader, alice, 30, 60_000);
        squads.join(alice, leader, 30);

        assertEquals(Status.NOT_LEADER, squads.transfer(alice, leader));
        assertEquals(Status.TARGET_NOT_MEMBER, squads.transfer(leader, bob));
        assertEquals(Status.SELF, squads.transfer(leader, leader));
        assertEquals(Status.OK, squads.transfer(leader, alice));
        assertTrue(squads.squadOf(leader).orElseThrow().isLeader(alice));
    }
}
