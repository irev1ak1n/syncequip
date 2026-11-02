package dev.syncequip.listeners;

import dev.syncequip.squad.JoinBlockService;
import dev.syncequip.squad.SquadManager;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Watches for players stepping onto a block whose material belongs to a squad.
 *
 * <p>Movement is the hot path with 100 players on a server, so almost every event leaves on the first cheap check:
 * only a move into a new block (or landing on one) is looked at, and only in worlds that have a joining block.
 * The material lookup is a single block read and a map lookup.
 */
public final class JoinBlockListener implements Listener {

    private final JoinBlockService service;
    private final SquadManager squads;

    public JoinBlockListener(JoinBlockService service, SquadManager squads) {
        this.service = service;
        this.squads = squads;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        // A new block, or touching down on top of one after being in the air.
        if (!event.hasChangedBlock() && !landed(event.getFrom(), to)) {
            return;
        }
        World world = to.getWorld();
        UUID worldId = world.getUID();
        boolean allWorlds = service.allWorlds();
        Player player = event.getPlayer();
        if (!squads.hasBlocks(worldId, allWorlds) || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        // Standing on a full block puts the feet exactly on its top, so the support is the block below. On a slab or
        // a carpet the feet are inside the block, which is then the support.
        int y = to.getBlockY();
        int supportY = to.getY() - y < 1.0E-4 ? y - 1 : y;
        Material support = world.getBlockAt(to.getBlockX(), supportY, to.getBlockZ()).getType();
        squads.blockFor(worldId, support, allWorlds).ifPresent(profile -> service.onStep(player, profile));
    }

    private static boolean landed(Location from, Location to) {
        return from.getY() != from.getBlockY() && to.getY() == to.getBlockY();
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        service.reindex();
    }
}
