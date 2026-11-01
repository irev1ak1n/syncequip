package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Squad membership and the saved settings of each squad, including joining blocks. Plain Java with no Bukkit
 * dependency. Members live in memory only. Settings that matter later (speed, loadout, joining block) are kept in
 * {@link SquadProfile}s, and {@link #setChangeListener} lets the plugin save them when they change.
 */
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
        SQUAD_FULL,
        NAME_INVALID,
        NAME_TAKEN,
        NAME_RESERVED,
        PAD_CONFLICT
    }

    public record LeaveResult(Status status, Squad squad, UUID newLeader, boolean disbanded) {
    }

    /** Letters, digits, underscore and hyphen, 2 to 24 characters. */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,24}");

    private final LongSupplier clock;
    private final Map<UUID, Squad> byPlayer = new HashMap<>();
    private final Map<UUID, Squad> byId = new HashMap<>();
    private final Map<String, Squad> byName = new HashMap<>();
    private final Map<String, SquadProfile> profiles = new LinkedHashMap<>();
    /** World id to packed block coordinates to profile. Only worlds that actually have pads appear here. */
    private final Map<UUID, Map<Long, SquadProfile>> padIndex = new HashMap<>();
    private final Map<String, UUID> indexedWorld = new HashMap<>();
    private Runnable changeListener = () -> { };

    public SquadManager(LongSupplier clock) {
        this.clock = clock;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void changed() {
        changeListener.run();
    }

    // ------------------------------------------------------------------ squads

    public Status create(UUID leader) {
        return create(leader, "squad-" + leader.toString().substring(0, 8), false);
    }

    /**
     * Creates a live squad. If a saved squad with the same name exists, its settings come back with it, but only
     * for its owner (or when {@code admin} is true).
     */
    public Status create(UUID leader, String name, boolean admin) {
        if (byPlayer.containsKey(leader)) {
            return Status.ALREADY_IN_SQUAD;
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return Status.NAME_INVALID;
        }
        String key = SquadProfile.key(name);
        if (byName.containsKey(key)) {
            return Status.NAME_TAKEN;
        }
        SquadProfile profile = profiles.get(key);
        if (profile != null && !profile.owner().equals(leader) && !admin) {
            return Status.NAME_RESERVED;
        }
        if (profile == null) {
            profile = new SquadProfile(name, leader);
            profiles.put(key, profile);
        } else if (!profile.owner().equals(leader)) {
            profile.setOwner(leader);
            changed();
        }
        Squad squad = new Squad(leader, profile);
        byPlayer.put(leader, squad);
        byId.put(squad.id(), squad);
        byName.put(key, squad);
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
        Squad squad = byPlayer.get(leader);
        if (byPlayer.containsKey(player)) {
            return Status.ALREADY_IN_SQUAD;
        }
        if (squad == null || !squad.isLeader(leader)) {
            return Status.NO_SUCH_SQUAD;
        }
        return joinInvited(player, squad, maxSize);
    }

    /** Joins a squad the player has an invite for. */
    public Status joinInvited(UUID player, Squad squad, int maxSize) {
        if (byPlayer.containsKey(player)) {
            return Status.ALREADY_IN_SQUAD;
        }
        if (!squad.hasValidInvite(player, clock.getAsLong())) {
            return Status.NOT_INVITED;
        }
        return admit(player, squad, maxSize);
    }

    /** Joins without an invite, for players stepping on a joining block. Never moves a player out of another squad. */
    public Status joinViaPad(UUID player, Squad squad, int maxSize) {
        if (byPlayer.containsKey(player)) {
            return Status.ALREADY_IN_SQUAD;
        }
        return admit(player, squad, maxSize);
    }

    private Status admit(UUID player, Squad squad, int maxSize) {
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
            removeLive(squad);
            if (!squad.profile().persistable()) {
                profiles.remove(squad.profile().key());
            }
            return new LeaveResult(Status.OK, squad, null, true);
        }
        // A leaving leader hands leadership to the member who has been in the squad the longest.
        UUID newLeader = null;
        if (wasLeader) {
            newLeader = squad.members().iterator().next();
            squad.setLeader(newLeader);
            changed();
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
        changed();
        return Status.OK;
    }

    /** Ends a squad for good: members are released and its saved settings and joining block are deleted. */
    public List<UUID> disband(String name) {
        String key = SquadProfile.key(name);
        List<UUID> released = new ArrayList<>();
        Squad squad = byName.get(key);
        if (squad != null) {
            released.addAll(squad.members());
            for (UUID member : released) {
                byPlayer.remove(member);
            }
            removeLive(squad);
        }
        SquadProfile profile = profiles.remove(key);
        if (profile != null) {
            unindex(profile);
            changed();
        }
        return released;
    }

    private void removeLive(Squad squad) {
        byId.remove(squad.id());
        byName.remove(squad.profile().key());
    }

    public Optional<Squad> squadOf(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    public Optional<Squad> squadById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Squad> squadByName(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byName.get(SquadProfile.key(name)));
    }

    public Collection<Squad> squads() {
        return Collections.unmodifiableCollection(byId.values());
    }

    /** Forgets every live squad. Saved settings stay, so squads can be brought back by name. */
    public void clear() {
        byPlayer.clear();
        byId.clear();
        byName.clear();
        profiles.values().removeIf(p -> !p.persistable());
    }

    // ------------------------------------------------------------------ saved settings

    public Optional<SquadProfile> profile(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(profiles.get(SquadProfile.key(name)));
    }

    public Collection<SquadProfile> profiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    /** Replaces the saved settings with what was read from disk. Call before any squad exists. */
    public void loadProfiles(Collection<SquadProfile> loaded) {
        profiles.clear();
        padIndex.clear();
        indexedWorld.clear();
        for (SquadProfile profile : loaded) {
            profiles.put(profile.key(), profile);
        }
    }

    public void setSpeed(SquadProfile profile, Integer ticks) {
        profile.setSpeed(ticks);
        changed();
    }

    public void setLoadout(SquadProfile profile, Loadout loadout) {
        profile.setLoadout(loadout);
        changed();
    }

    // ------------------------------------------------------------------ joining blocks

    /**
     * Registers the joining block of a squad, replacing its old one. Fails if another squad already uses the block.
     *
     * @param worldId the id the world currently has, used for fast lookups
     */
    public Status setPad(SquadProfile profile, Pad pad, UUID worldId) {
        SquadProfile owner = padAt(worldId, pad.x(), pad.y(), pad.z()).orElse(null);
        if (owner != null && owner != profile) {
            return Status.PAD_CONFLICT;
        }
        unindex(profile);
        profile.setPad(pad);
        index(profile, worldId);
        changed();
        return Status.OK;
    }

    public boolean removePad(SquadProfile profile) {
        if (profile.pad() == null) {
            return false;
        }
        unindex(profile);
        profile.setPad(null);
        profile.setPadEnabled(true);
        if (!profile.persistable() && !byName.containsKey(profile.key())) {
            profiles.remove(profile.key());
        }
        changed();
        return true;
    }

    public void setPadEnabled(SquadProfile profile, boolean enabled) {
        profile.setPadEnabled(enabled);
        changed();
    }

    public Optional<SquadProfile> padAt(UUID worldId, int x, int y, int z) {
        Map<Long, SquadProfile> world = padIndex.get(worldId);
        return world == null ? Optional.empty() : Optional.ofNullable(world.get(Pad.key(x, y, z)));
    }

    /** Cheap test used before looking up a block while a player walks. */
    public boolean hasPads(UUID worldId) {
        return padIndex.containsKey(worldId);
    }

    /**
     * Rebuilds the lookup from the saved pads. The resolver turns a saved pad into the id of the world that is
     * loaded now, or {@code null} if that world is not loaded.
     */
    public void reindexPads(Function<Pad, UUID> resolver) {
        padIndex.clear();
        indexedWorld.clear();
        for (SquadProfile profile : profiles.values()) {
            if (profile.pad() != null) {
                UUID worldId = resolver.apply(profile.pad());
                if (worldId != null) {
                    index(profile, worldId);
                }
            }
        }
    }

    private void index(SquadProfile profile, UUID worldId) {
        Pad pad = profile.pad();
        padIndex.computeIfAbsent(worldId, id -> new HashMap<>()).put(pad.key(), profile);
        indexedWorld.put(profile.key(), worldId);
    }

    private void unindex(SquadProfile profile) {
        UUID worldId = indexedWorld.remove(profile.key());
        if (worldId == null || profile.pad() == null) {
            return;
        }
        Map<Long, SquadProfile> world = padIndex.get(worldId);
        if (world != null) {
            world.remove(profile.pad().key(), profile);
            if (world.isEmpty()) {
                padIndex.remove(worldId);
            }
        }
    }
}
