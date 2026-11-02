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

    /**
     * Titles draw at 4x and subtitles at 2x the GUI scale. At 1080p with GUI scale 4 the screen is 480 units wide, so a
     * title gets about 18 characters and a subtitle about 40. These limits leave a margin and also fit scale 3 and below.
     */
    @Test
    void everyStageTitleAndSubtitleFitsOnScreen() {
        run(viewer, "demo", "tour");
        java.util.Set<String> titles = new java.util.LinkedHashSet<>();
        for (int t = 0; t < 12000 && plugin.tour().running(); t++) {
            server.getScheduler().performOneTick();
            for (String line : messages(viewer)) {
                int at = line.indexOf("[Tour] ");
                if (at < 0 || !line.substring(at + 7).matches("[1-8]/8 .*")) {
                    continue;
                }
                String[] parts = line.substring(at + 7).split(" - ", 2);
                assertTrue(parts[0].length() <= 16, "title too long: " + parts[0]);
                assertTrue(parts.length == 2 && parts[1].length() <= 36, "subtitle too long: " + line);
                assertTrue(parts[0].matches("[1-8]/8 — .+"), "stage numbering kept: " + parts[0]);
                titles.add(parts[0].substring(0, 1));
            }
        }
        assertEquals(8, titles.size(), "all eight stages were shown: " + titles);
    }

    /** A stage's demonstration starts only after its title has faded out (0.25 + 2.5 + 0.5 s = 65 ticks) plus a pause. */
    @Test
    void everyDemonstrationWaitsForItsTitleToFadeAndThenAShortPause() {
        run(viewer, "demo", "tour");
        int now = 0;
        int titleAt = -1;
        int stages = 0;
        int checked = 0;
        for (; now < 12000 && plugin.tour().running(); now++) {
            server.getScheduler().performOneTick();
            if (messages(viewer).stream().anyMatch(m -> m.contains("[Tour] ") && m.matches(".*\\[Tour\\] [1-8]/8 .*"))) {
                titleAt = now;
                stages++;
            }
            boolean busy = bots.stream().anyMatch(b -> plugin.sequenceService().scheduler().isBusy(b.getUniqueId()));
            if (busy && titleAt >= 0) {
                assertTrue(now - titleAt >= 80, "action began " + (now - titleAt) + " ticks after its title");
                checked++;
                titleAt = -1;
            }
        }
        assertEquals(11, stages, "eight stages, speed stage shown four times");
        assertTrue(checked >= 8, "sequence stages checked: " + checked);
    }

    @Test
    void theTourNeedsExactlyTheConfiguredNumberOfBotsAndUsesFive() {
        assertEquals(5, plugin.tour().required());
        bots.get(4).kick();
        server.getScheduler().performOneTick();
        run(viewer, "demo", "tour");
        assertFalse(plugin.tour().running());
        assertTrue(received(viewer, "5 test players"));
    }

    @Test
    void everyBotEndsUpOnObsidianInTheSquadAndInAnEvenLine() {
        run(viewer, "demo", "tour");
        boolean checked = false;
        for (int t = 0; t < 12000 && plugin.tour().running(); t++) {
            server.getScheduler().performOneTick();
            if (!checked && bots.stream().allMatch(b -> plugin.sequenceService().scheduler().isBusy(b.getUniqueId()))) {
                checked = true;
                for (PlayerMock bot : bots) {
                    assertTrue(plugin.squads().squadOf(bot.getUniqueId()).isPresent(), bot.getName() + " in the squad");
                    assertEquals(Material.OBSIDIAN, bot.getLocation().getBlock().getRelative(0, -1, 0).getType(),
                            bot.getName() + " stands on obsidian");
                }
                for (int i = 1; i < bots.size(); i++) {
                    double gap = bots.get(i).getLocation().distance(bots.get(i - 1).getLocation());
                    assertEquals(2.0, gap, 0.01, "even spacing");
                }
            }
        }
        assertTrue(checked, "all five took part in a synchronized sequence");
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
        for (PlayerMock bot : bots) {
            assertTrue(received(bot, "[SE-TOUR] goto"), bot.getName() + " gets the walk cue, the leader included");
        }
    }
}
