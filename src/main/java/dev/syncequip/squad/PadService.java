package dev.syncequip.squad;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.util.MessageUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/**
 * The behaviour behind joining blocks: registering them, and adding players when they step on one. A pad is one
 * exact block (world and coordinates), never a block type.
 */
public final class PadService {

    public static final String PERMISSION_JOIN = "syncequip.squad";

    public enum SetResult {
        OK,
        NOT_SOLID,
        CONFLICT
    }

    private final Server server;
    private final SquadManager squads;
    private final MessageUtil messages;
    private final Supplier<SyncEquipSettings> settings;
    private final LongSupplier clock;
    /** When each player last heard a problem about a pad, so standing on one never fills the chat. */
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    private final Map<UUID, Long> lastLeaderBar = new HashMap<>();

    public PadService(Server server, SquadManager squads, MessageUtil messages, Supplier<SyncEquipSettings> settings,
                      LongSupplier clock) {
        this.server = server;
        this.squads = squads;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ stepping on a pad

    /** Called when a player's feet reach the block above a registered pad. */
    public void onStep(Player player, SquadProfile profile) {
        SyncEquipSettings.Pads pads = settings.get().pads();
        if (!pads.enabled() || !profile.padEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        Optional<Squad> squad = squads.squadByName(profile.name());
        if (squad.isEmpty()) {
            notice(player, "pad.inactive", ph("squad", profile.name()));
            return;
        }
        Optional<Squad> current = squads.squadOf(id);
        if (current.isPresent()) {
            if (current.get() != squad.get()) {
                notice(player, "pad.in-other-squad", ph("squad", profile.name()), ph("current", current.get().name()));
            }
            return;
        }
        if (!player.hasPermission(PERMISSION_JOIN)) {
            notice(player, "command.no-permission");
            return;
        }
        SquadManager.Status status = squads.joinViaPad(id, squad.get(), settings.get().maxSquadSize());
        switch (status) {
            case OK -> {
                messages.send(player, "pad.joined", ph("squad", squad.get().name()));
                lastNotice.remove(id);
                tellLeader(squad.get());
            }
            case SQUAD_FULL -> notice(player, "pad.full", ph("squad", profile.name()),
                    ph("max", settings.get().maxSquadSize()));
            default -> {
            }
        }
    }

    private void tellLeader(Squad squad) {
        Player leader = server.getPlayer(squad.leader());
        if (leader == null) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastLeaderBar.getOrDefault(leader.getUniqueId(), 0L) < 250) {
            return;
        }
        lastLeaderBar.put(leader.getUniqueId(), now);
        messages.actionBar(leader, "pad.leader-count", ph("squad", squad.name()), ph("count", squad.size()));
    }

    private void notice(Player player, String key, TagResolver... resolvers) {
        long now = clock.getAsLong();
        long wait = settings.get().pads().noticeCooldownSeconds() * 1000L;
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < wait) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        messages.send(player, key, resolvers);
    }

    public void forget(UUID player) {
        lastNotice.remove(player);
        lastLeaderBar.remove(player);
    }

    // ------------------------------------------------------------------ registering

    /** The block directly under the player's feet: a slab they stand on, or the block below an open space. */
    public static Block blockUnder(Location location) {
        Block feet = location.getBlock();
        return feet.getType().isSolid() ? feet : feet.getRelative(BlockFace.DOWN);
    }

    public SetResult set(Player actor, SquadProfile profile) {
        Block block = blockUnder(actor.getLocation());
        if (!block.getType().isSolid()) {
            return SetResult.NOT_SOLID;
        }
        World world = block.getWorld();
        Pad pad = new Pad(world.getUID(), world.getName(), block.getX(), block.getY(), block.getZ());
        SquadManager.Status status = squads.setPad(profile, pad, world.getUID());
        return status == SquadManager.Status.OK ? SetResult.OK : SetResult.CONFLICT;
    }

    // ------------------------------------------------------------------ keeping the registry honest

    /** Looks up the pad registered at a block, if any. */
    public Optional<SquadProfile> padAt(Block block) {
        UUID world = block.getWorld().getUID();
        if (!squads.hasPads(world)) {
            return Optional.empty();
        }
        return squads.padAt(world, block.getX(), block.getY(), block.getZ());
    }

    /** A pad block was broken, blown up or moved: the registration goes away, and the leader hears about it. */
    public void padDestroyed(SquadProfile profile) {
        Pad pad = profile.pad();
        if (pad == null || !squads.removePad(profile)) {
            return;
        }
        Optional<Squad> squad = squads.squadByName(profile.name());
        Player notify = squad.map(s -> server.getPlayer(s.leader())).orElse(null);
        if (notify == null) {
            notify = server.getPlayer(profile.owner());
        }
        if (notify != null) {
            messages.send(notify, "pad.destroyed", ph("squad", profile.name()), ph("x", pad.x()), ph("y", pad.y()),
                    ph("z", pad.z()));
        }
    }

    /** Re-reads which saved pads belong to loaded worlds. Call after worlds load or unload. */
    public void reindex() {
        squads.reindexPads(pad -> {
            World world = pad.worldId() != null ? server.getWorld(pad.worldId()) : null;
            if (world == null) {
                world = server.getWorld(pad.worldName());
            }
            return world == null ? null : world.getUID();
        });
    }

    /** Checks that the block of a pad is still a solid block, and drops the registration if it is not. */
    public boolean stillValid(SquadProfile profile) {
        Pad pad = profile.pad();
        if (pad == null) {
            return false;
        }
        World world = pad.worldId() != null ? server.getWorld(pad.worldId()) : null;
        if (world == null) {
            world = server.getWorld(pad.worldName());
        }
        return world != null && world.getBlockAt(pad.x(), pad.y(), pad.z()).getType().isSolid();
    }
}
