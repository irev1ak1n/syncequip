package dev.syncequip.listeners;

import dev.syncequip.squad.PadService;
import dev.syncequip.squad.SquadManager;
import java.util.List;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Watches for players stepping on joining blocks, and for those blocks being destroyed.
 *
 * <p>Movement is the hot path with 100 players on a server, so the handler exits on the first cheap check for
 * almost every event: it only looks further when the player entered a new block, and only if the world has any pad.
 */
public final class PadListener implements Listener {

    private final PadService pads;
    private final SquadManager squads;

    public PadListener(PadService pads, SquadManager squads) {
        this.pads = pads;
        this.squads = squads;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        Location to = event.getTo();
        UUID world = to.getWorld().getUID();
        if (!squads.hasPads(world) || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        int x = to.getBlockX();
        int y = to.getBlockY();
        int z = to.getBlockZ();
        // Standing on a full block puts the feet one block above it. On a slab the feet are inside the block.
        squads.padAt(world, x, y - 1, z)
                .or(() -> squads.padAt(world, x, y, z))
                .ifPresent(profile -> pads.onStep(player, profile));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        pads.padAt(event.getBlock()).ifPresent(pads::padDestroyed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        checkAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        checkAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkAll(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkAll(event.getBlocks());
    }

    private void checkAll(List<Block> blocks) {
        if (blocks.isEmpty() || !squads.hasPads(blocks.getFirst().getWorld().getUID())) {
            return;
        }
        for (Block block : blocks) {
            pads.padAt(block).ifPresent(pads::padDestroyed);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        pads.reindex();
    }
}
