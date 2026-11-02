package dev.syncequip.listeners;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.squad.SquadSequenceController;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public final class HotkeyListener implements Listener {
    public static final String PERMISSION = "syncequip.hotkey";

    private final SyncEquipPlugin plugin;

    public HotkeyListener(SyncEquipPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        SyncEquipSettings settings = plugin.settings();
        Player player = event.getPlayer();
        if (!settings.controls().sneakSwapTrigger() || !player.isSneaking() || !player.hasPermission(PERMISSION)) {
            return;
        }
        event.setCancelled(true);
        // Holding the keys down must not queue up repeated sequences, so a busy player is ignored here.
        if (plugin.sequenceService().scheduler().isBusy(player.getUniqueId())) {
            return;
        }
        boolean leadsSquad = plugin.squads().squadOf(player.getUniqueId())
                .map(squad -> SquadSequenceController.canControl(player, squad))
                .orElse(false);
        if (settings.controls().sneakSwapAction() == SyncEquipSettings.HotkeyAction.AUTO && leadsSquad) {
            plugin.squadController().start(player);
        } else {
            plugin.sequenceService().startSolo(player);
        }
    }
}
