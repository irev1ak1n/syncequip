package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Squad {

    private final UUID id = UUID.randomUUID();
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, Long> invites = new HashMap<>();
    private final SquadProfile profile;
    private UUID leader;

    Squad(UUID leader, SquadProfile profile) {
        this.leader = leader;
        this.profile = profile;
        members.add(leader);
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return profile.name();
    }

    public SquadProfile profile() {
        return profile;
    }

    /** The squad's own speed, or the global default when it has none. */
    public int effectiveSpeed(int globalDefault) {
        return profile.speed() != null ? profile.speed() : globalDefault;
    }

    /** The squad's own loadout, or the global default when it has none. */
    public Loadout effectiveLoadout(Loadout globalDefault) {
        return profile.loadout() != null ? profile.loadout() : globalDefault;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    public int size() {
        return members.size();
    }

    void setLeader(UUID leader) {
        this.leader = leader;
        profile.setOwner(leader);
    }

    void addMember(UUID player) {
        members.add(player);
        invites.remove(player);
    }

    void removeMember(UUID player) {
        members.remove(player);
    }

    void invite(UUID player, long expiresAt) {
        invites.put(player, expiresAt);
    }

    boolean hasValidInvite(UUID player, long now) {
        Long expiresAt = invites.get(player);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= now) {
            invites.remove(player);
            return false;
        }
        return true;
    }
}
