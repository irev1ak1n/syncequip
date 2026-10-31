package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class HotkeyTest extends PluginTestBase {

    private PlayerSwapHandItemsEvent pressSwap(PlayerMock player) {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player,
                player.getInventory().getItemInOffHand(), player.getInventory().getItemInMainHand());
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void disabledByDefault() {
        PlayerMock player = playerWithKit("Steve");
        player.setSneaking(true);
        assertFalse(pressSwap(player).isCancelled());
        tick(15);
        assertEquals(0, countEquipped(player));
    }

    @Test
    void sneakSwapStartsSequenceAndCancelsTheSwap() {
        setConfig("controls.sneak-swap-trigger", true);
        PlayerMock player = playerWithKit("Steve");
        player.setSneaking(true);

        assertTrue(pressSwap(player).isCancelled());
        tick(15);
        assertEquals(5, countEquipped(player));
        assertEquals(Material.IRON_SWORD, player.getInventory().getItemInMainHand().getType());
    }

    @Test
    void plainSwapStillWorks() {
        setConfig("controls.sneak-swap-trigger", true);
        PlayerMock player = playerWithKit("Steve");
        player.setSneaking(false);
        assertFalse(pressSwap(player).isCancelled());
        tick(15);
        assertEquals(0, countEquipped(player));
    }

    @Test
    void repeatedPressesDoNotQueueSequences() {
        setConfig("controls.sneak-swap-trigger", true);
        setConfig("solo.countdown-seconds", 1);
        PlayerMock player = playerWithKit("Steve");
        player.setSneaking(true);
        for (int i = 0; i < 5; i++) {
            pressSwap(player);
            tick(1);
        }
        assertEquals(1, plugin.sequenceService().scheduler().activeSequences().size());
        tick(40);
        assertEquals(5, countEquipped(player));
    }

    @Test
    void autoModeStartsTheSquadForItsLeader() {
        setConfig("controls.sneak-swap-trigger", true);
        setConfig("controls.sneak-swap-action", "AUTO");
        PlayerMock leader = playerWithKit("Leader");
        PlayerMock member = playerWithKit("Member");
        run(leader, "squad", "create");
        run(leader, "squad", "invite", "Member");
        run(member, "squad", "join", "Leader");

        leader.setSneaking(true);
        pressSwap(leader);
        tick(80);
        assertEquals(5, countEquipped(leader));
        assertEquals(5, countEquipped(member));
    }

    @Test
    void lockedSwapDuringEquippingIsCancelled() {
        PlayerMock player = playerWithKit("Steve");
        player.getInventory().setItem(13, null);
        player.getInventory().setItem(14, new ItemStack(Material.SHIELD));
        run(player, "solo");
        tick(1);
        assertTrue(pressSwap(player).isCancelled());
    }
}
