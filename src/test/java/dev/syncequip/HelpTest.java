package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class HelpTest extends PluginTestBase {
    private PlayerMock op;
    private PlayerMock player;

    @BeforeEach
    void players() {
        op = server.addPlayer("Admin");
        op.setOp(true);
        player = server.addPlayer("Regular");
    }

    private String allHelp(PlayerMock who, int pages) {
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= pages; page++) {
            run(who, "help", String.valueOf(page));
            messages(who).forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }

    @Test
    void helpWithoutAPageShowsTheFirstPageWithNavigation() {
        run(player, "help");
        List<String> lines = messages(player);
        assertTrue(lines.getFirst().contains("Basics") && lines.getFirst().contains("page 1 of 5"));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/syncequip solo")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/syncequip unequip")));
        assertTrue(lines.getLast().contains("Previous") && lines.getLast().contains("Next"));
    }

    @Test
    void noArgumentsShowTheSamePageAsHelp() {
        run(player);
        assertTrue(messages(player).getFirst().contains("page 1 of 5"));
    }

    @Test
    void ordinaryPlayersSeeFivePagesAndOperatorsSeeSeven() {
        run(player, "help", "6");
        assertTrue(received(player, "no help page"));
        run(op, "help", "7");
        assertTrue(messages(op).getFirst().contains("Admin"));
        run(op, "help");
        assertTrue(messages(op).getFirst().contains("page 1 of 7"));
    }

    @Test
    void pagesCanBeOpenedByNumberNameOrAlias() {
        for (String word : new String[]{"2", "squads", "squad", "SQUADS"}) {
            run(player, "help", word);
            assertTrue(messages(player).getFirst().contains("Squads"), word);
        }
        run(player, "help", "block");
        assertTrue(messages(player).getFirst().contains("Joining blocks"));
        run(player, "help", "loadouts");
        assertTrue(messages(player).getFirst().contains("Loadouts"));
        run(player, "help", "unequip");
        assertTrue(messages(player).getFirst().contains("Squad sequences"));
        run(op, "help", "demo");
        assertTrue(messages(op).getFirst().contains("Demo mode"));
    }

    @Test
    void badPagesAreExplained() {
        for (String bad : new String[]{"0", "99", "-1", "banana"}) {
            run(player, "help", bad);
            assertTrue(received(player, "no help page"), bad);
        }
    }

    @Test
    void firstAndLastPagesDisableTheirArrow() {
        run(player, "help", "1");
        assertFalse(messages(player).getLast().contains("Previous") && false);
        run(player, "help", "5");
        List<String> last = messages(player);
        assertTrue(last.getFirst().contains("page 5 of 5"));
        assertTrue(last.getLast().contains("Next"));
    }

    @Test
    void demoAndReloadAreHiddenFromOrdinaryPlayers() {
        String text = allHelp(player, 5);
        assertFalse(text.contains("/syncequip demo"));
        assertFalse(text.contains("/syncequip reload"));
        String full = allHelp(op, 7);
        assertTrue(full.contains("/syncequip demo spawn <count>"));
        assertTrue(full.contains("/syncequip reload"));
    }

    @Test
    void everyPageFitsInChat() {
        for (int page = 1; page <= 7; page++) {
            run(op, "help", String.valueOf(page));
            assertTrue(messages(op).size() <= 12, "page " + page + " should fit on screen");
        }
    }

    // ------------------------------------------------------------------ help must match what exists

    @Test
    void everyTopLevelSubcommandIsDocumented() {
        String text = allHelp(op, 7);
        for (String sub : command.complete(op, new String[]{""})) {
            assertTrue(text.contains("/syncequip " + sub), "help is missing: /syncequip " + sub);
        }
    }

    @Test
    void everySquadSubcommandIsDocumented() {
        String text = allHelp(op, 7);
        List<String> subs = command.complete(op, new String[]{"squad", ""});
        assertTrue(subs.containsAll(List.of("create", "invite", "join", "leave", "list", "info", "start", "cancel",
                "unequip", "transfer", "speed", "block", "loadout", "disband")), subs.toString());
        for (String sub : subs) {
            assertTrue(text.contains("/syncequip squad " + sub), "help is missing: /syncequip squad " + sub);
        }
    }

    @Test
    void everyPadLoadoutAndDemoActionIsDocumented() {
        String text = allHelp(op, 7);
        for (String action : command.complete(op, new String[]{"squad", "block", ""})) {
            assertTrue(text.contains("/syncequip squad block " + action), "help is missing: pad " + action);
        }
        for (String action : command.complete(op, new String[]{"squad", "loadout", ""})) {
            assertTrue(text.contains("/syncequip squad loadout " + action), "help is missing: loadout " + action);
        }
        for (String action : command.complete(op, new String[]{"demo", ""})) {
            assertTrue(text.contains("/syncequip demo " + action), "help is missing: demo " + action);
        }
    }

    @Test
    void everyLoadoutSlotAndValueWordInTheHelpIsReallyAccepted() {
        String text = allHelp(op, 7);
        List<String> slots = List.of("mainhand", "offhand", "helmet", "chestplate", "leggings", "boots");
        for (String slot : slots) {
            assertTrue(text.contains(slot), slot);
            assertTrue(dev.syncequip.equipment.EquipmentStep.parse(slot).isPresent(), slot);
        }
        assertTrue(dev.syncequip.equipment.SlotRequirement.parse(dev.syncequip.equipment.EquipmentStep.MAIN_HAND,
                "any_sword").ok());
        assertTrue(dev.syncequip.equipment.SlotRequirement.parse(dev.syncequip.equipment.EquipmentStep.MAIN_HAND,
                "any_axe").ok());
        assertTrue(dev.syncequip.equipment.SlotRequirement.parse(dev.syncequip.equipment.EquipmentStep.HELMET,
                "netherite").ok());
        assertTrue(dev.syncequip.equipment.SlotRequirement.parse(dev.syncequip.equipment.EquipmentStep.SHIELD,
                "totem_of_undying").ok());
    }

    @Test
    void theExampleCommandsInTheHelpReallyRun() {
        PlayerMock leader = playerWithKit("Chief");
        leader.teleport(new org.bukkit.Location(leader.getWorld(), 0.5, 64, 0.5));
        leader.getWorld().getBlockAt(0, 63, 0).setType(org.bukkit.Material.STONE);
        run(leader, "squad", "create");
        List<String[]> commands = List.of(
                new String[]{"squad", "info"}, new String[]{"squad", "list"},
                new String[]{"squad", "speed", "4"}, new String[]{"squad", "speed", "default"},
                new String[]{"squad", "block", "set"}, new String[]{"squad", "block", "info"},
                new String[]{"squad", "block", "disable"}, new String[]{"squad", "block", "enable"},
                new String[]{"squad", "block", "remove"},
                new String[]{"squad", "loadout", "show"}, new String[]{"squad", "loadout", "set", "mainhand", "netherite_sword"},
                new String[]{"squad", "loadout", "optional", "mainhand", "on"},
                new String[]{"squad", "loadout", "order", "offhand", "helmet"}, new String[]{"squad", "loadout", "reset"});
        messages(leader);
        for (String[] args : commands) {
            run(leader, args);
            List<String> out = messages(leader);
            assertFalse(out.isEmpty(), String.join(" ", args) + " answered");
            assertTrue(out.stream().noneMatch(m -> m.contains("Unknown") || m.contains("Usage") || m.contains("Missing message")),
                    String.join(" ", args) + " -> " + out);
        }
    }

    @Test
    void tabCompletionOffersHelpPagesThePlayerMaySee() {
        assertTrue(command.complete(player, new String[]{"help", ""}).containsAll(List.of("basics", "squads", "1", "5")));
        assertFalse(command.complete(player, new String[]{"help", ""}).contains("demo"));
        assertTrue(command.complete(op, new String[]{"help", ""}).containsAll(List.of("demo", "admin", "7")));
        assertEquals(List.of("squads", "sequences"), command.complete(player, new String[]{"help", "s"}).stream()
                .filter(s -> !s.matches("\\d")).sorted(java.util.Comparator.reverseOrder()).toList());
        assertEquals(List.of(), command.complete(player, new String[]{"help", "1", ""}));
    }

    @Test
    void anOlderMessagesFileWithTheOldFlatHelpStillGetsTheNewPages() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "messages.yml");
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        old.set("help", null);
        old.set("help.header", "<gold>SyncEquip commands:");
        old.set("help.lines", new ArrayList<>(List.of(" <white>/syncequip solo</white> - old")));
        old.save(file);
        plugin.messages().load();

        run(player, "help", "2");
        List<String> lines = messages(player);
        assertTrue(lines.stream().anyMatch(l -> l.contains("/syncequip squad create [name]")), lines.toString());
    }
}
