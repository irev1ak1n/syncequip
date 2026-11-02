package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class TourTest extends V11TestBase {

    private PlayerMock viewer;
    private final List<PlayerMock> bots = new ArrayList<>();

    @BeforeEach
    void people() {
        viewer = server.addPlayer("Watcher");
        viewer.setOp(true);
        viewer.teleport(new Location(world, 0.5, 64, 0.5, 0, 0));
        for (int i = 1; i <= 5; i++) {
            bots.add(server.addPlayer("TourBot" + i));
        }
        messages(viewer);
    }

    private boolean anyObsidian() {
        for (int x = -15; x <= 15; x++) {
            for (int z = -15; z <= 15; z++) {
                if (world.getBlockAt(x, world.getHighestBlockYAt(x, z), z).getType() == Material.OBSIDIAN) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void withoutTestPlayersTheTourExplainsWhatToStart() {
        bots.forEach(b -> b.kick());
        server.getScheduler().performOneTick();
        run(viewer, "demo", "tour");
        assertTrue(received(viewer, "start-tour.bat"));
        assertFalse(plugin.tour().running());
    }

    @Test
    void theWholeTourRunsAndEveryoneEndsFullyEquippedThenEverythingIsPutBack() {
        run(viewer, "demo", "tour");
        assertTrue(plugin.tour().running());
        int best = 0;
        boolean sawSquad = false;
        boolean sawObsidian = false;
        for (int t = 0; t < 12000 && plugin.tour().running(); t++) {
            server.getScheduler().performOneTick();
            sawSquad |= plugin.squads().squadByName("NULL").map(s -> s.size() == 5).orElse(false);
            sawObsidian |= t % 20 == 0 && anyObsidian();
            if (t % 5 == 0) {
                best = Math.max(best, bots.stream().mapToInt(PluginTestBase::countEquipped).min().orElse(0));
            }
        }
        assertFalse(plugin.tour().running(), "the tour finishes by itself");
        assertTrue(sawSquad, "all five joined squad NULL");
        assertTrue(sawObsidian, "the obsidian strip was placed");
        assertEquals(5, best, "all five wore the complete set at the same time");
        assertTrue(received(viewer, "8/8"));
        assertFalse(anyObsidian(), "the strip was removed");
        assertTrue(plugin.squads().squadByName("NULL").isEmpty());
        assertTrue(plugin.squads().profile("NULL").isEmpty());
        for (PlayerMock bot : bots) {
            assertEquals(0, countEquipped(bot));
            assertTrue(java.util.Arrays.stream(bot.getInventory().getContents()).allMatch(i -> i == null || i.isEmpty()));
        }
    }

    @Test
    void theViewersOwnInventoryIsNeverTouched() {
        viewer.getInventory().setItem(3, new ItemStack(Material.DIAMOND, 7));
        run(viewer, "demo", "tour");
        tick(12000);
        assertEquals(7, viewer.getInventory().getItem(3).getAmount());
        assertEquals(1, java.util.Arrays.stream(viewer.getInventory().getContents()).filter(i -> i != null && !i.isEmpty()).count());
    }

    @Test
    void pausingHoldsTheScriptAndStoppingCleansUp() {
        run(viewer, "demo", "tour");
        tick(40);
        run(viewer, "demo", "tour", "pause");
        assertTrue(plugin.tour().paused());
        tick(600);
        assertTrue(plugin.squads().squadByName("NULL").isEmpty(), "still stuck at the formation stage");
        run(viewer, "demo", "tour", "resume");
        tick(300);
        assertTrue(plugin.squads().squadByName("NULL").isPresent());
        run(viewer, "demo", "tour", "stop");
        assertFalse(plugin.tour().running());
        assertFalse(anyObsidian());
        assertTrue(plugin.squads().squadByName("NULL").isEmpty());
    }

    @Test
    void restartReplaysFromTheStartAndStartingTwiceIsRefused() {
        run(viewer, "demo", "tour");
        run(viewer, "demo", "tour");
        assertTrue(received(viewer, "already running"));
        tick(400);
        run(viewer, "demo", "tour", "restart");
        assertTrue(plugin.tour().running());
        assertTrue(plugin.squads().squadByName("NULL").isEmpty());
        tick(12000);
        assertFalse(plugin.tour().running());
        assertFalse(anyObsidian());
    }

    @Test
    void botsAreToldToWalkWithAPrivateCue() {
        run(viewer, "demo", "tour");
        tick(400);
        assertTrue(received(bots.get(1), "[SE-TOUR] walk"), "joiners get the walk cue");
        assertFalse(received(bots.get(0), "[SE-TOUR] walk"), "the leader is already in the squad");
    }
}
