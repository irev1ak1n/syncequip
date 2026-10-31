package dev.syncequip.squad;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class SquadManager {

    public enum Status {
        OK,
        ALREADY_IN_SQUAD,
        NOT_IN_SQUAD,
        NOT_LEADER,
        SELF,
        TARGET_ALREADY_MEMBER,
        TARGET_IN_OTHER_SQUAD,
        TARGET_NOT_MEMBER,
        NO_SUCH_SQUAD,
        NOT_INVITED,
        SQUAD_FULL
    }

    public record LeaveResult(Status status, Squad squad, UUID newLeader, boolean disbanded) {
    }

    private final LongSupplier clock;
    private final Map<UUID, Squad> byPlayer = new HashMap<>();
    private final Map<UUID, Squad> byId = new HashMap<>();

    public SquadManager(LongSupplier clock) {
        this.clock = clock;
    }

    public Status create(UUID leader) {
        if (byPlayer.containsKey(leader)) {
            return Status.ALREADY_IN_SQUAD;
        }
        Squad squad = new Squad(leader);
        byPlayer.put(leader, squad);
        byId.put(squad.id(), squad);
        return Status.OK;
    }

    public Status invite(UUID inviter, UUID target, int maxSize, long expiryMillis) {
        Squad squad = byPlayer.get(inviter);
        if (squad == null) {
            return Status.NOT_IN_SQUAD;
        }
        if (!squad.isLeader(inviter)) {
            return Status.NOT_LEADER;
        }
        if (inviter.equals(target)) {
            return Status.SELF;
        }
        if (squad.isMember(target)) {
            return Status.TARGET_ALREADY_MEMBER;
        }
        if (squad.size() >= maxSize) {
            return Status.SQUAD_FULL;
        }
        squad.invite(target, clock.getAsLong() + expiryMillis);
        return byPlayer.containsKey(target) ? Status.TARGET_IN_OTHER_SQUAD : Status.OK;
    }

    public Status join(UUID player, UUID leader, int maxSize) {
        if (byPlayer.containsKey(player)) {
            return Status.ALREADY_IN_SQUAD;
        }
        Squad squad = byPlayer.get(leader);
        if (squad == null || !squad.isLeader(leader)) {
            return Status.NO_SUCH_SQUAD;
        }
        if (!squad.hasValidInvite(player, clock.getAsLong())) {
            return Status.NOT_INVITED;
        }
        if (squad.size() >= maxSize) {
            return Status.SQUAD_FULL;
        }
        squad.addMember(player);
        byPlayer.put(player, squad);
        return Status.OK;
    }

    public LeaveResult leave(UUID player) {
        Squad squad = byPlayer.remove(player);
        if (squad == null) {
            return new LeaveResult(Status.NOT_IN_SQUAD, null, null, false);
        }
        boolean wasLeader = squad.isLeader(player);
        squad.removeMember(player);
        if (squad.size() == 0) {
            byId.remove(squad.id());
            return new LeaveResult(Status.OK, squad, null, true);
        }
        // A leaving leader hands leadership to the member who has been in the squad the longest.
        UUID newLeader = null;
        if (wasLeader) {
            newLeader = squad.members().iterator().next();
            squad.setLeader(newLeader);
        }
        return new LeaveResult(Status.OK, squad, newLeader, false);
    }

    public Status transfer(UUID leader, UUID target) {
        Squad squad = byPlayer.get(leader);
        if (squad == null) {
            return Status.NOT_IN_SQUAD;
        }
        if (!squad.isLeader(leader)) {
            return Status.NOT_LEADER;
        }
        if (leader.equals(target)) {
            return Status.SELF;
        }
        if (!squad.isMember(target)) {
            return Status.TARGET_NOT_MEMBER;
        }
        squad.setLeader(target);
        return Status.OK;
    }

    public Optional<Squad> squadOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Squad> squadById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Collection<Squad> squads() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public void clear() {
        byPlayer.clear();
        byId.clear();
    }
}
