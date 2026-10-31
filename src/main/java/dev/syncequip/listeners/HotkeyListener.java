package dev.syncequip.listeners;

import dev.syncequip.SyncEquipPlugin;
import dev.syncequip.config.SyncEquipSettings;
import dev.syncequip.squad.SquadSequenceController;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Optional activation by <b>sneaking while pressing the swap-hands key</b> (Shift + F by default).
 *
 * <p>The server cannot see keyboard input; it sees the <em>swap hands action</em>, which the client
 * sends whenever the player presses their swap key, and it knows whether the player is sneaking.
 * When the trigger fires the swap itself is cancelled so the offhand is not disturbed. Plain F
 * (without sneaking) keeps working exactly as in vanilla.
 */
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
        if (plugin.sequenceService().scheduler().isBusy(player.getUniqueId())) {
            return; // holding the keys must not queue repeated sequences
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
