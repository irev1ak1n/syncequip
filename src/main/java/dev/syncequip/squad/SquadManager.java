package dev.syncequip.squad;

import dev.syncequip.equipment.Loadout;
import org.bukkit.Material;
import java.util.ArrayList;
import java.util.EnumMap;
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
        BLOCK_CONFLICT
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
    /** World id to block material to squad. Only worlds that have a joining block appear here. */
    private final Map<UUID, Map<Material, SquadProfile>> blockIndex = new HashMap<>();
    /** Material to squad for the all-worlds setting. A world specific assignment always wins over this. */
    private final Map<Material, SquadProfile> anyWorld = new EnumMap<>(Material.class);
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
    public Status joinViaBlock(UUID player, Squad squad, int maxSize) {
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
        blockIndex.clear();
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
     * Gives a squad its joining block material in a world, replacing the one it had. Fails if another squad already
     * uses that material in the same world.
     */
    public Status setBlock(SquadProfile profile, Material material, String worldName, UUID worldId) {
        SquadProfile holder = blockIndex.getOrDefault(worldId, Map.of()).get(material);
        if (holder != null && holder != profile) {
            return Status.BLOCK_CONFLICT;
        }
        unindex(profile);
        profile.setBlock(material, worldName, worldId);
        // Giving a squad a joining block always turns joining on, even if an earlier one had been switched off.
        profile.setBlockEnabled(true);
        index(profile, worldId);
        changed();
        return Status.OK;
    }

    public boolean removeBlock(SquadProfile profile) {
        if (profile.blockMaterial() == null) {
            return false;
        }
        unindex(profile);
        profile.clearBlock();
        if (!profile.persistable() && !byName.containsKey(profile.key())) {
            profiles.remove(profile.key());
        }
        changed();
        return true;
    }

    public void setBlockEnabled(SquadProfile profile, boolean enabled) {
        profile.setBlockEnabled(enabled);
        changed();
    }

    /** Forgets the note about an old coordinate based joining block once the leader has been told. */
    public void clearLegacyPad(SquadProfile profile) {
        profile.setLegacyPad(null);
        changed();
    }

    /** The squad whose joining block this is, looking at this world first and then, if allowed, every world. */
    public Optional<SquadProfile> blockFor(UUID worldId, Material material, boolean allWorlds) {
        Map<Material, SquadProfile> world = blockIndex.get(worldId);
        SquadProfile hit = world == null ? null : world.get(material);
        if (hit == null && allWorlds) {
            hit = anyWorld.get(material);
        }
        return Optional.ofNullable(hit);
    }

    /** Cheap test used before reading a block while a player walks. */
    public boolean hasBlocks(UUID worldId, boolean allWorlds) {
        return blockIndex.containsKey(worldId) || (allWorlds && !anyWorld.isEmpty());
    }

    /**
     * Rebuilds the lookup from the saved assignments. The resolver gives the id of the world a squad's block belongs
     * to, or {@code null} if that world is not loaded.
     */
    public void reindexBlocks(Function<SquadProfile, UUID> resolver) {
        blockIndex.clear();
        indexedWorld.clear();
        for (SquadProfile profile : profiles.values()) {
            if (profile.blockMaterial() != null) {
                UUID worldId = resolver.apply(profile);
                if (worldId != null) {
                    blockIndex.computeIfAbsent(worldId, id -> new EnumMap<>(Material.class))
                            .put(profile.blockMaterial(), profile);
                    indexedWorld.put(profile.key(), worldId);
                }
            }
        }
        rebuildAnyWorld();
    }

    private void index(SquadProfile profile, UUID worldId) {
        blockIndex.computeIfAbsent(worldId, id -> new EnumMap<>(Material.class)).put(profile.blockMaterial(), profile);
        indexedWorld.put(profile.key(), worldId);
        rebuildAnyWorld();
    }

    private void unindex(SquadProfile profile) {
        UUID worldId = indexedWorld.remove(profile.key());
        if (worldId != null && profile.blockMaterial() != null) {
            Map<Material, SquadProfile> world = blockIndex.get(worldId);
            if (world != null) {
                world.remove(profile.blockMaterial(), profile);
                if (world.isEmpty()) {
                    blockIndex.remove(worldId);
                }
            }
        }
        rebuildAnyWorld();
    }

    private void rebuildAnyWorld() {
        anyWorld.clear();
        for (SquadProfile profile : profiles.values()) {
            if (profile.blockMaterial() != null && indexedWorld.containsKey(profile.key())) {
                anyWorld.putIfAbsent(profile.blockMaterial(), profile);
            }
        }
    }
}
