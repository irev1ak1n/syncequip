package dev.syncequip.listeners;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.synchronization.RemovalReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps sequences and squads consistent with what players do: leaving, dying and changing worlds
 * remove a player from their sequence, and inventory edits are blocked for the fraction of a second
 * in which their equipment is actively changing.
 */
public final class PlayerListener implements Listener {

    private final SyncEquipPlugin plugin;

    public PlayerListener(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.sequenceService().scheduler().removeParticipant(player.getUniqueId(), RemovalReason.DISCONNECTED);
        plugin.leaveSquad(player.getUniqueId(), RemovalReason.DISCONNECTED);
        plugin.cooldowns().clear(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.sequenceService().scheduler().removeParticipant(event.getPlayer().getUniqueId(), RemovalReason.DIED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.sequenceService().scheduler().removeParticipant(event.getPlayer().getUniqueId(),
                RemovalReason.CHANGED_WORLD);
    }

    // ------------------------------------------------------------------ inventory lock while equipping

    private boolean locked(Player player) {
        return plugin.sequenceService().isLocked(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Stops right-click armor equipping from racing the sequence for the same slot. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !locked(event.getPlayer())) {
            return;
        }
        for (EquipmentStep step : EquipmentStep.values()) {
            if (step.matches(item.getType())) {
                event.setUseItemInHand(Event.Result.DENY);
                return;
            }
        }
    }
}
