package dev.syncequip.squad;

import static dev.syncequip.util.MessageUtil.ph;

import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.util.MessageUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * The behaviour behind joining blocks. A squad is given a block material, and anyone who steps onto a block of
 * that material in the squad's world joins it, wherever the block is and whenever it was placed.
 */
public final class JoinBlockService {

    public static final String PERMISSION_JOIN = "syncequip.squad";

    public enum SetResult {
        OK,
        NOT_SOLID,
        CONFLICT
    }

    /** A material read from a command, or the reason it cannot be used. */
    public record ParsedMaterial(Material material, String error) {

        public boolean ok() {
            return material != null;
        }
    }

    private final Server server;
    private final SquadManager squads;
    private final MessageUtil messages;
    private final Supplier<SyncEquipSettings> settings;
    private final LongSupplier clock;
    /** When each player last heard a problem about a joining block, so standing on one never fills the chat. */
    private final Map<UUID, Long> lastNotice = new HashMap<>();
    private final Map<UUID, Long> lastLeaderBar = new HashMap<>();

    public JoinBlockService(Server server, SquadManager squads, MessageUtil messages,
                            Supplier<SyncEquipSettings> settings, LongSupplier clock) {
        this.server = server;
        this.squads = squads;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
    }

    public boolean allWorlds() {
        return settings.get().blocks().allWorlds();
    }

    // ------------------------------------------------------------------ stepping onto a block

    /** Called when a player steps onto a block whose material belongs to a squad. */
    public void onStep(Player player, SquadProfile profile) {
        SyncEquipSettings.Blocks blocks = settings.get().blocks();
        if (!blocks.enabled() || !profile.blockEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        Optional<Squad> squad = squads.squadByName(profile.name());
        if (squad.isEmpty()) {
            notice(player, "block.inactive", ph("squad", profile.name()));
            return;
        }
        Optional<Squad> current = squads.squadOf(id);
        if (current.isPresent()) {
            if (current.get() != squad.get()) {
                notice(player, "block.in-other-squad", ph("squad", profile.name()), ph("current", current.get().name()));
            }
            return;
        }
        if (!player.hasPermission(PERMISSION_JOIN)) {
            notice(player, "command.no-permission");
            return;
        }
        SquadManager.Status status = squads.joinViaBlock(id, squad.get(), settings.get().maxSquadSize());
        switch (status) {
            case OK -> {
                messages.send(player, "block.joined", ph("squad", squad.get().name()));
                lastNotice.remove(id);
                tellLeader(squad.get());
            }
            case SQUAD_FULL -> notice(player, "block.full", ph("squad", profile.name()),
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
        messages.actionBar(leader, "block.leader-count", ph("squad", squad.name()), ph("count", squad.size()));
    }

    private void notice(Player player, String key, TagResolver... resolvers) {
        long now = clock.getAsLong();
        long wait = settings.get().blocks().noticeCooldownSeconds() * 1000L;
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

    /** The material of the block a player at this location is standing on. */
    public static Material supportMaterial(Location at) {
        int y = at.getBlockY();
        // Feet exactly on a block top means the block below. Feet inside a slab or carpet means that block.
        int supportY = at.getY() - y < 1.0E-4 ? y - 1 : y;
        return at.getWorld().getBlockAt(at.getBlockX(), supportY, at.getBlockZ()).getType();
    }

    /**
     * Adds the players who are already standing on the squad's joining block, who would otherwise have to step off
     * and on again. The player who made the change is left out.
     */
    public void sweep(SquadProfile profile, Player except) {
        Material material = profile.blockMaterial();
        if (material == null || !profile.blockEnabled()) {
            return;
        }
        boolean all = allWorlds();
        for (Player player : server.getOnlinePlayers()) {
            UUID worldId = player.getWorld().getUID();
            if (player == except || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || supportMaterial(player.getLocation()) != material
                    || squads.blockFor(worldId, material, all).orElse(null) != profile) {
                continue;
            }
            onStep(player, profile);
        }
    }

    // ------------------------------------------------------------------ choosing the material

    /** The block directly under the player's feet: a slab they stand on, or the block below an open space. */
    public static Block blockUnder(Location location) {
        Block feet = location.getBlock();
        return feet.getType().isSolid() ? feet : feet.getRelative(BlockFace.DOWN);
    }

    /** Reads a block name such as {@code obsidian} or {@code minecraft:crying_obsidian}. */
    public static ParsedMaterial parseMaterial(String word) {
        String name = word.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        Material material = Material.matchMaterial(name);
        if (material == null || material.isLegacy() || !material.isBlock()) {
            return new ParsedMaterial(null, "'" + word.trim() + "' is not a block I know.");
        }
        if (!material.isSolid()) {
            return new ParsedMaterial(null, pretty(material) + " is not a solid block, so nobody can stand on it.");
        }
        return new ParsedMaterial(material, null);
    }

    public static String pretty(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Gives the squad a joining block material in the actor's world.
     *
     * @param material the material, or {@code null} to use the block under the actor's feet
     */
    public SetResult set(Player actor, SquadProfile profile, Material material) {
        Material chosen = material != null ? material : blockUnder(actor.getLocation()).getType();
        if (!chosen.isSolid() || !chosen.isBlock()) {
            return SetResult.NOT_SOLID;
        }
        World world = actor.getWorld();
        SquadManager.Status status = squads.setBlock(profile, chosen, world.getName(), world.getUID());
        return status == SquadManager.Status.OK ? SetResult.OK : SetResult.CONFLICT;
    }

    /** Re-reads which saved assignments belong to loaded worlds. Call after worlds load. */
    public void reindex() {
        squads.reindexBlocks(profile -> {
            World world = profile.blockWorldId() != null ? server.getWorld(profile.blockWorldId()) : null;
            if (world == null && profile.blockWorldName() != null) {
                world = server.getWorld(profile.blockWorldName());
            }
            return world == null ? null : world.getUID();
        });
    }
}
