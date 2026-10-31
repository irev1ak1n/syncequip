package dev.syncequip.squad;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A group of players who equip together. The leader is always also a member. */
public final class Squad {

    private final UUID id = UUID.randomUUID();
    private final Set<UUID> members = new LinkedHashSet<>();
    /** Invited player -> wall-clock millis at which the invite expires. */
    private final Map<UUID, Long> invites = new HashMap<>();
    private UUID leader;

    Squad(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    /** Members in join order, leader first at creation. */
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
