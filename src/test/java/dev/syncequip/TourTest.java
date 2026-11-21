package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class TourTest extends V11TestBase {
    private static final List<String> STAGES = List.of("Squad Formation", "Block Join", "Synchronized Equip",
            "Speed Control", "Custom Loadout");
    private static final Pattern TITLE = Pattern.compile("\\[Tour\\] (" + String.join("|", STAGES) + ") - (.*)");
    private static final Pattern CUE = Pattern.compile("\\[SE-TOUR\\] goto (-?[\\d.]+) (-?[\\d.]+)");
    /** About how long a real test player needs to walk from the line onto the obsidian. */
    private static final int WALK_TICKS = 30;

    private record Shown(int tick, String title, String subtitle) {
    }

    private PlayerMock viewer;
    private final List<PlayerMock> bots = new ArrayList<>();
    private final Map<PlayerMock, double[]> walking = new HashMap<>();
    private final List<Shown> shown = new ArrayList<>();
    private final List<String> heard = new ArrayList<>();
    private int clock;

    @BeforeEach
    void people() {
        viewer = server.addPlayer("Watcher");
        viewer.setOp(true);
        viewer.teleport(new Location(world, 0.5, 64, 0.5, 0, 0));
        for (int i = 1; i <= 5; i++) {
            bots.add(server.addPlayer("TourBot" + i));
        }
        messages(viewer);
        // The real cooldown stays on: the tour must not be stopped by it.
        setConfig("controls.cooldown-seconds", 3);
    }

    /** One tick, with the bots doing what tour.js does: a walk cue is followed a moment later by a step onto the block. */
    private void tickOnce() {
        server.getScheduler().performOneTick();
        clock++;
        for (PlayerMock bot : bots) {
            for (String line : messages(bot)) {
                Matcher cue = CUE.matcher(line);
                if (cue.find()) {
                    walking.put(bot, new double[] {Double.parseDouble(cue.group(1)), Double.parseDouble(cue.group(2)),
                            clock + WALK_TICKS});
                }
            }
        }
        walking.entrySet().removeIf(entry -> {
            double[] walk = entry.getValue();
            if (walk[2] > clock) {
                return false;
            }
            int x = (int) Math.floor(walk[0]);
            int z = (int) Math.floor(walk[1]);
            for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
                if (world.getBlockAt(x, y, z).getType() == Material.OBSIDIAN) {
                    PlayerMock bot = entry.getKey();
                    Location target = new Location(world, x + 0.5, y + 1, z + 0.5);
                    bot.teleport(target);
                    plugin.squads().profile("TestGroup").ifPresent(profile -> plugin.joinBlocks().onStep(bot, profile));
                    break;
                }
            }
            return true;
        });
        for (String line : messages(viewer)) {
            heard.add(line);
            Matcher title = TITLE.matcher(line.substring(Math.max(0, line.indexOf("[Tour] "))));
            if (title.find()) {
                shown.add(new Shown(clock, title.group(1), title.group(2)));
            }
        }
    }

    private void tickWithBots(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tickOnce();
        }
    }

    /** Runs the tour to the end and returns how many ticks it took. */
    private int playTour() {
        run(viewer, "demo", "tour");
        assertTrue(plugin.tour().running());
        int start = clock;
        while (plugin.tour().running() && clock - start < 12000) {
            tickOnce();
        }
        assertFalse(plugin.tour().running(), "the tour finishes by itself");
        return clock - start;
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

    private boolean busy() {
        return bots.stream().anyMatch(b -> plugin.sequenceService().scheduler().isBusy(b.getUniqueId()));
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
    void theTourHasExactlyTheFiveStagesInOrder() {
        playTour();
        List<String> order = new ArrayList<>();
        for (Shown s : shown) {
            if (order.isEmpty() || !order.getLast().equals(s.title())) {
                order.add(s.title());
            }
        }
        assertEquals(STAGES, order);
        assertEquals(6, shown.size(), "five stages, the speed stage shown once per speed");
    }

    /**
     * Titles draw at 4x and subtitles at 2x the GUI scale. At 1080p with GUI scale 4 the screen is 480 units wide, so a
     * title gets about 18 characters and a subtitle about 40. These limits leave a margin and also fit scale 3 and below.
     */
    @Test
    void everyStageTitleAndSubtitleFitsOnScreen() {
        playTour();
        for (Shown s : shown) {
            assertTrue(s.title().length() <= 18, "title too long: " + s.title());
            assertTrue(s.subtitle().length() <= 36, "subtitle too long: " + s.subtitle());
            assertTrue(s.subtitle().matches("[1-5]/5 · .+"), "stage number kept: " + s.subtitle());
        }
    }

    @Test
    void theSpeedStageShowsASlowAndAFastDelayPerPiece() {
        playTour();
        List<String> speeds = shown.stream().filter(s -> s.title().equals("Speed Control")).map(Shown::subtitle).toList();
        assertEquals(2, speeds.size());
        assertTrue(speeds.get(0).contains("Slow") && speeds.get(0).contains("10 ticks per piece"), speeds.get(0));
        assertTrue(speeds.get(1).contains("Fast") && speeds.get(1).contains("1 tick per piece"), speeds.get(1));
    }

    /** A title stays up for 0.7 s (14 ticks) and the stage's action starts straight after it. */
    @Test
    void everyActionStartsRightAfterItsTitleHasGone() {
        run(viewer, "demo", "tour");
        int titleAt = -1;
        int checked = 0;
        int seen = 0;
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
            if (shown.size() > seen) {
                seen = shown.size();
                titleAt = clock;
            }
            if (busy() && titleAt >= 0) {
                int gap = clock - titleAt;
                assertTrue(gap >= 14 && gap <= 16, "action began " + gap + " ticks after its title");
                checked++;
                titleAt = -1;
            }
        }
        assertEquals(4, checked, "equip, slow unequip, fast equip and the custom loadout begin right after their titles");
    }

    @Test
    void theWholeTourTakesAboutTwentyFiveSeconds() {
        int ticks = playTour();
        assertTrue(ticks >= 20 * 20 && ticks <= 28 * 20, "the tour took " + ticks / 20.0 + " s, titles at " + shown);
    }

    private record Sample(int tick, int[] worn, Material[] hand, Material[] offhand, boolean[] helmet) {
    }

    private List<Sample> recordTour() {
        List<Sample> samples = new ArrayList<>();
        run(viewer, "demo", "tour");
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
            boolean[] helmets = new boolean[bots.size()];
            for (int i = 0; i < helmets.length; i++) {
                helmets[i] = bots.get(i).getInventory().getHelmet() != null;
            }
            samples.add(new Sample(clock,
                    bots.stream().mapToInt(PluginTestBase::countEquipped).toArray(),
                    bots.stream().map(b -> b.getInventory().getItemInMainHand().getType()).toArray(Material[]::new),
                    bots.stream().map(b -> b.getInventory().getItemInOffHand().getType()).toArray(Material[]::new),
                    helmets));
        }
        return samples;
    }

    /** Ticks on which the (identical) worn count rose or fell, grouped into one run per sequence. */
    private static List<List<Integer>> runs(List<Sample> samples, boolean rising) {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int last = samples.getFirst().worn()[0];
        int lastTick = 0;
        for (Sample sample : samples) {
            int now = sample.worn()[0];
            if (rising ? now > last : now < last) {
                if (!current.isEmpty() && sample.tick() - lastTick > 14) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
                current.add(sample.tick());
                lastTick = sample.tick();
            }
            last = now;
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        runs.removeIf(run -> run.size() < 3);
        return runs;
    }

    private static double averageGap(List<Integer> run) {
        return (run.getLast() - run.getFirst()) / (double) (run.size() - 1);
    }

    private static boolean allBare(Sample sample) {
        return java.util.Arrays.stream(sample.worn()).allMatch(c -> c == 0);
    }

    @Test
    void speedControlVisiblyEquipsSlowlyThenQuicklyAfterClearingTheBots() {
        List<Sample> samples = recordTour();
        for (Sample sample : samples) {
            for (int count : sample.worn()) {
                assertEquals(sample.worn()[0], count, "all five bots are in the same state at tick " + sample.tick());
            }
        }
        List<List<Integer>> equips = runs(samples, true);
        List<List<Integer>> unequips = runs(samples, false);
        assertEquals(4, equips.size(), "full equip, slow equip, fast equip, custom equip: " + equips);
        assertEquals(4, unequips.size(), "two in speed control, two in the custom loadout: " + unequips);
        assertEquals(10.0, averageGap(equips.get(1)), 1.0, "slow equip, ticks per piece: " + equips.get(1));
        assertEquals(1.0, averageGap(equips.get(2)), 0.5, "fast equip, ticks per piece: " + equips.get(2));
        assertTrue(equips.get(1).getLast() - equips.get(1).getFirst() >= 25, "the slow equip is slow enough to watch");
        assertTrue(equips.get(2).getLast() - equips.get(2).getFirst() <= 8, "the fast equip is over in a moment");
        assertTrue(unequips.get(0).getFirst() > equips.get(0).getLast());
        assertTrue(unequips.get(0).getLast() < equips.get(1).getFirst());
        assertTrue(equips.get(1).getLast() < unequips.get(1).getFirst());
        assertTrue(unequips.get(1).getLast() < equips.get(2).getFirst());
        for (int[] window : new int[][] {{unequips.get(0).getLast(), equips.get(1).getFirst()},
                {unequips.get(1).getLast(), equips.get(2).getFirst()}}) {
            assertTrue(samples.stream().anyMatch(s -> s.tick() > window[0] && s.tick() < window[1] && allBare(s)),
                    "every bot was bare before the next equip");
        }
        for (int run = 1; run <= 2; run++) {
            int end = equips.get(run).getLast();
            Sample done = samples.stream().filter(s -> s.tick() == end).findFirst().orElseThrow();
            assertTrue(java.util.Arrays.stream(done.worn()).allMatch(c -> c == 5), "all five complete after equip " + run);
        }
    }

    @Test
    void customLoadoutIsClearedAppliedHeldForAWhileAndClearedAgain() {
        List<Sample> samples = recordTour();
        List<List<Integer>> equips = runs(samples, true);
        List<List<Integer>> unequips = runs(samples, false);
        int equipEnd = equips.get(3).getLast();
        assertTrue(unequips.get(2).getLast() < equips.get(3).getFirst(), "cleared before the custom equip");
        assertTrue(samples.stream().anyMatch(s -> s.tick() > unequips.get(2).getLast()
                && s.tick() < equips.get(3).getFirst() && allBare(s)), "bare before the custom equip");
        long shownFor = samples.stream().filter(s -> s.tick() >= equipEnd && s.tick() < unequips.get(3).getFirst()
                && java.util.Arrays.stream(s.hand()).allMatch(m -> m == Material.NETHERITE_AXE)
                && java.util.Arrays.stream(s.offhand()).allMatch(m -> m == Material.TOTEM_OF_UNDYING)
                && java.util.stream.IntStream.range(0, s.helmet().length).noneMatch(k -> s.helmet()[k])).count();
        assertTrue(shownFor >= 40, "axe, totem and no helmet stayed on all five for " + shownFor + " ticks");
        assertTrue(allBare(samples.getLast()), "everything is taken off again at the end");
    }

    @Test
    void aBotThatLostItsKitIsRestockedSoTheEquipStillHappens() {
        run(viewer, "demo", "tour");
        boolean wiped = false;
        boolean fullAgain = false;
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
            if (!wiped && shown.stream().anyMatch(s -> s.subtitle().contains("Slow"))
                    && bots.stream().allMatch(b -> countEquipped(b) == 0)) {
                wiped = true;
                bots.get(3).getInventory().clear();
            } else if (wiped) {
                fullAgain |= bots.stream().allMatch(b -> countEquipped(b) == 5);
            }
        }
        assertTrue(wiped);
        assertTrue(fullAgain, "all five, including the one that lost its items, wore the full set afterwards");
        assertFalse(plugin.tour().running());
    }

    @Test
    void theWholeTourRunsAndTheLoadoutsAreShownThenEverythingIsPutBack() {
        run(viewer, "demo", "tour");
        int best = 0;
        boolean sawSquad = false;
        boolean sawObsidian = false;
        boolean sawCustom = false;
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
            sawSquad |= plugin.squads().squadByName("TestGroup").map(s -> s.size() == 5).orElse(false);
            sawObsidian |= clock % 20 == 0 && anyObsidian();
            best = Math.max(best, bots.stream().mapToInt(PluginTestBase::countEquipped).min().orElse(0));
            sawCustom |= bots.stream().allMatch(b -> b.getInventory().getItemInMainHand().getType() == Material.NETHERITE_AXE
                    && b.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING
                    && b.getInventory().getHelmet() == null);
        }
        assertFalse(plugin.tour().running(), "the tour finishes by itself");
        assertTrue(sawSquad, "all five joined squad TestGroup");
        assertTrue(sawObsidian, "the obsidian strip was placed");
        assertEquals(5, best, "all five wore the complete set at the same time");
        assertTrue(sawCustom, "all five held the axe and the totem with no helmet");
        assertFalse(anyObsidian(), "the strip was removed");
        assertTrue(plugin.squads().squadByName("TestGroup").isEmpty());
        assertTrue(plugin.squads().profile("TestGroup").isEmpty());
        for (PlayerMock bot : bots) {
            assertEquals(0, countEquipped(bot));
            assertTrue(java.util.Arrays.stream(bot.getInventory().getContents()).allMatch(i -> i == null || i.isEmpty()));
        }
    }

    @Test
    void theTourEndsWithOneSynchronizedUnequipAndNoSwappingOrFinaleStages() {
        playTour();
        assertTrue(shown.stream().noneMatch(s -> s.title().contains("Swapping") || s.title().contains("Finale")));
        assertEquals("Custom Loadout", shown.getLast().title());
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
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
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
    void theViewersOwnInventoryIsNeverTouched() {
        viewer.getInventory().setItem(3, new ItemStack(Material.DIAMOND, 7));
        playTour();
        assertEquals(7, viewer.getInventory().getItem(3).getAmount());
        assertEquals(1, java.util.Arrays.stream(viewer.getInventory().getContents()).filter(i -> i != null && !i.isEmpty()).count());
    }

    @Test
    void pausingHoldsTheScriptAndStoppingCleansUp() {
        run(viewer, "demo", "tour");
        tickWithBots(10);
        run(viewer, "demo", "tour", "pause");
        assertTrue(plugin.tour().paused());
        tickWithBots(600);
        assertTrue(plugin.squads().squadByName("TestGroup").isEmpty(), "still held at the formation stage");
        run(viewer, "demo", "tour", "resume");
        tickWithBots(120);
        assertTrue(plugin.squads().squadByName("TestGroup").isPresent());
        run(viewer, "demo", "tour", "stop");
        assertFalse(plugin.tour().running());
        assertFalse(anyObsidian());
        assertTrue(plugin.squads().squadByName("TestGroup").isEmpty());
    }

    @Test
    void restartReplaysFromTheStartAndStartingTwiceIsRefused() {
        run(viewer, "demo", "tour");
        run(viewer, "demo", "tour");
        assertTrue(received(viewer, "already running"));
        tickWithBots(150);
        run(viewer, "demo", "tour", "restart");
        assertTrue(plugin.tour().running());
        assertTrue(plugin.squads().squadByName("TestGroup").isEmpty());
        tickWithBots(12000);
        assertFalse(plugin.tour().running());
        assertFalse(anyObsidian());
    }

    @Test
    void botsAreToldToWalkWithAPrivateCue() {
        run(viewer, "demo", "tour");
        tick(60);
        for (PlayerMock bot : bots) {
            assertTrue(received(bot, "[SE-TOUR] goto"), bot.getName() + " gets the walk cue, the leader included");
        }
    }

    @Test
    void aMissingBotIsWaitedForAndTheTourCarriesOn() {
        run(viewer, "demo", "tour");
        tickWithBots(5);
        bots.get(2).kick();
        tickWithBots(100);
        assertTrue(plugin.tour().running(), "still waiting for the missing test player");
        assertTrue(heard.stream().anyMatch(m -> m.contains("TourBot3 is not online")));
        bots.set(2, server.addPlayer("TourBot3"));
        for (int i = 0; i < 12000 && plugin.tour().running(); i++) {
            tickOnce();
        }
        assertFalse(plugin.tour().running());
        assertTrue(shown.stream().anyMatch(s -> s.title().equals("Custom Loadout")), "the tour reached its last stage");
    }

    @Test
    void aBotOutOfPositionIsMovedBackBeforeTheNextStage() {
        run(viewer, "demo", "tour");
        PlayerMock wanderer = bots.get(1);
        boolean moved = false;
        while (plugin.tour().running() && clock < 12000) {
            tickOnce();
            if (!moved && plugin.squads().squadByName("TestGroup").map(s -> s.size() == 5).orElse(false)) {
                moved = true;
                wanderer.teleport(wanderer.getLocation().add(9, 0, 9));
            }
        }
        assertTrue(moved);
        assertFalse(plugin.tour().running());
        assertTrue(shown.stream().anyMatch(s -> s.title().equals("Custom Loadout")), "the tour still reached its last stage");
    }
}
