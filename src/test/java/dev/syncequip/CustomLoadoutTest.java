package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.syncequip.equipment.EquipmentStep;
import dev.syncequip.equipment.Loadout;
import dev.syncequip.equipment.SoloLoadouts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The in-game solo and group loadout commands for all six slots. */
class CustomLoadoutTest extends V11TestBase {
    private PlayerMock player;

    @BeforeEach
    void person() {
        player = server.addPlayer("Streamer");
        messages(player);
    }

    private void give(int slot, Material material) {
        player.getInventory().setItem(slot, new ItemStack(material));
    }

    private ItemStack worn(EquipmentSlot slot) {
        return player.getInventory().getItem(slot);
    }

    private void solo(String... words) {
        String[] args = new String[words.length + 1];
        args[0] = "solo";
        System.arraycopy(words, 0, args, 1, words.length);
        run(player, args);
    }

    @Test
    void everySlotNameWorksIncludingTheOldOnes() {
        for (String word : List.of("mainhand", "offhand", "head", "chest", "legs", "feet", "helmet", "chestplate",
                "leggings", "boots")) {
            assertTrue(EquipmentStep.parse(word).isPresent(), word);
        }
        assertEquals(EquipmentStep.HELMET, EquipmentStep.parse("head").orElseThrow());
        assertEquals(EquipmentStep.BOOTS, EquipmentStep.parse("feet").orElseThrow());
    }

    @Test
    void soloEquipsAllSixSlotsIncludingNonstandardWearables() {
        solo("set", "mainhand", "netherite_axe");
        solo("set", "offhand", "totem_of_undying");
        solo("set", "head", "carved_pumpkin");
        solo("set", "chest", "elytra");
        solo("set", "legs", "chainmail_leggings");
        solo("set", "feet", "diamond_boots");
        give(0, Material.NETHERITE_AXE);
        give(9, Material.TOTEM_OF_UNDYING);
        give(10, Material.CARVED_PUMPKIN);
        give(11, Material.ELYTRA);
        give(12, Material.CHAINMAIL_LEGGINGS);
        give(13, Material.DIAMOND_BOOTS);
        messages(player);

        run(player, "solo");
        tick(200);

        assertEquals(Material.CARVED_PUMPKIN, worn(EquipmentSlot.HEAD).getType());
        assertEquals(Material.ELYTRA, worn(EquipmentSlot.CHEST).getType());
        assertEquals(Material.CHAINMAIL_LEGGINGS, worn(EquipmentSlot.LEGS).getType());
        assertEquals(Material.DIAMOND_BOOTS, worn(EquipmentSlot.FEET).getType());
        assertEquals(Material.TOTEM_OF_UNDYING, worn(EquipmentSlot.OFF_HAND).getType());
        assertEquals(Material.NETHERITE_AXE, worn(EquipmentSlot.HAND).getType());
    }

    @Test
    void aPlayerHeadCanBeWornOnTheHead() {
        solo("set", "head", "player_head");
        assertTrue(received(player, "Helmet now needs"));
    }

    @Test
    void itemsThatCannotBeWornInASlotAreRefusedWithAHelpfulMessage() {
        solo("set", "head", "elytra");
        assertTrue(received(player, "does not fit the head slot"));
        solo("set", "legs", "iron_sword");
        assertTrue(received(player, "does not fit the legs slot"));
        solo("set", "feet", "carved_pumpkin");
        assertTrue(received(player, "does not fit the feet slot"));
        solo("set", "chest", "iron_helmet");
        assertTrue(received(player, "does not fit the chest slot"));
        assertTrue(plugin.soloLoadouts().get(player.getUniqueId()).isEmpty(), "nothing was saved");
    }

    @Test
    void unknownSlotsAndItemsAreExplained() {
        solo("set", "tail", "stone");
        assertTrue(received(player, "is not a slot"));
        solo("set", "head", "not_an_item");
        assertTrue(received(player, "not an item I know"));
        solo("set", "head");
        assertTrue(received(player, "Usage"));
    }

    @Test
    void anyItemFitsInEitherHand() {
        solo("set", "mainhand", "stone");
        solo("set", "offhand", "diamond_pickaxe");
        Loadout loadout = plugin.soloLoadouts().get(player.getUniqueId()).orElseThrow();
        assertEquals("stone", loadout.requirement(EquipmentStep.MAIN_HAND).token());
        assertEquals("diamond_pickaxe", loadout.requirement(EquipmentStep.SHIELD).token());
    }

    @Test
    void offTurnsASlotOffAndTheSequenceSkipsIt() {
        solo("set", "offhand", "off");
        give(0, Material.IRON_SWORD);
        give(9, Material.IRON_BOOTS);
        give(13, Material.SHIELD);
        run(player, "solo");
        tick(200);
        assertFalse(has(player, EquipmentSlot.OFF_HAND), "the shield stayed in the inventory");
        assertTrue(has(player, EquipmentSlot.FEET));
    }

    @Test
    void aCustomSoloLoadoutBeatsSoloUsesDefault() {
        setConfig("loadout.solo-uses-default", true);
        solo("set", "mainhand", "off");
        give(3, Material.IRON_SWORD);
        give(9, Material.IRON_BOOTS);
        run(player, "solo");
        tick(200);
        assertTrue(has(player, EquipmentSlot.FEET));
        assertEquals(Material.IRON_SWORD, player.getInventory().getItem(3).getType(), "the sword stayed where it was");
        assertFalse(has(player, EquipmentSlot.HAND), "the main hand was switched off by the custom loadout");
    }

    @Test
    void withSoloUsesDefaultAndNoCustomLoadoutTheSwordIsEquipped() {
        setConfig("loadout.solo-uses-default", true);
        give(3, Material.IRON_SWORD);
        give(9, Material.IRON_BOOTS);
        give(10, Material.IRON_LEGGINGS);
        give(11, Material.IRON_CHESTPLATE);
        give(12, Material.IRON_HELMET);
        give(13, Material.SHIELD);
        run(player, "solo");
        tick(200);
        assertTrue(has(player, EquipmentSlot.HAND));
    }

    @Test
    void withoutACustomLoadoutSoloKeepsItsOriginalBehaviour() {
        giveKit(player);
        run(player, "solo");
        tick(200);
        assertEquals(5, countEquipped(player));
        assertEquals(Material.IRON_SWORD, player.getInventory().getItem(0).getType(), "sword not touched");
    }

    @Test
    void showAndResetWork() {
        solo("set", "head", "carved_pumpkin");
        messages(player);
        solo("show");
        List<String> shown = messages(player);
        assertTrue(shown.stream().anyMatch(m -> m.contains("your own")), shown.toString());
        assertTrue(shown.stream().anyMatch(m -> m.contains("carved_pumpkin")), shown.toString());
        for (String slot : List.of("Main hand", "Offhand", "Helmet", "Chestplate", "Leggings", "Boots")) {
            assertTrue(shown.stream().anyMatch(m -> m.contains(slot + ":")), slot + " listed in " + shown);
        }

        solo("reset");
        assertTrue(plugin.soloLoadouts().get(player.getUniqueId()).isEmpty());
        solo("show");
        assertTrue(received(player, "server default"));
    }

    @Test
    void tabCompletionSuggestsSlotsAndWearableItems() {
        assertEquals(List.of("set", "show", "reset"), command.complete(player, new String[] {"solo", ""}));
        assertEquals(LoadoutCompletions.SLOTS, command.complete(player, new String[] {"solo", "set", ""}));
        List<String> head = command.complete(player, new String[] {"solo", "set", "head", "carved"});
        assertTrue(head.contains("carved_pumpkin"), head.toString());
        List<String> chest = command.complete(player, new String[] {"solo", "set", "chest", "el"});
        assertTrue(chest.contains("elytra"), chest.toString());
        List<String> feet = command.complete(player, new String[] {"solo", "set", "feet", "iron_"});
        assertTrue(feet.contains("iron_boots") && !feet.contains("iron_sword"), feet.toString());
        List<String> hand = command.complete(player, new String[] {"solo", "set", "mainhand", "diamond_p"});
        assertTrue(hand.contains("diamond_pickaxe"), hand.toString());
        assertTrue(command.complete(player, new String[] {"solo", "set", "head", ""}).contains("off"));
    }

    // ------------------------------------------------------------------ groups

    @Test
    void groupSetChangesTheSquadLoadoutWithOrWithoutTheGroupName() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        run(leader, "group", "set", "TestGroup", "head", "netherite_helmet");
        run(leader, "group", "set", "offhand", "totem_of_undying");
        Loadout loadout = plugin.squads().squadOf(leader.getUniqueId()).orElseThrow().profile().loadout();
        assertNotNull(loadout);
        assertEquals("netherite_helmet", loadout.requirement(EquipmentStep.HELMET).token());
        assertEquals("totem_of_undying", loadout.requirement(EquipmentStep.SHIELD).token());
        assertEquals("any_sword", loadout.requirement(EquipmentStep.MAIN_HAND).token(), "other slots untouched");
    }

    @Test
    void groupShowAndResetWork() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        run(leader, "group", "set", "chest", "elytra");
        messages(leader);
        run(leader, "group", "show");
        assertTrue(received(leader, "elytra"));
        run(leader, "group", "reset", "TestGroup");
        assertTrue(plugin.squads().squadOf(leader.getUniqueId()).orElseThrow().profile().loadout() == null);
    }

    @Test
    void groupWorksAsAnAliasAndSquadStillWorks() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        run(leader, "squad", "loadout", "set", "helmet", "diamond_helmet");
        run(leader, "group", "loadout", "set", "boots", "diamond_boots");
        run(leader, "squad", "loadout", "set", "feet", "netherite_boots");
        Loadout loadout = plugin.squads().squadOf(leader.getUniqueId()).orElseThrow().profile().loadout();
        assertEquals("diamond_helmet", loadout.requirement(EquipmentStep.HELMET).token());
        assertEquals("netherite_boots", loadout.requirement(EquipmentStep.BOOTS).token());
    }

    @Test
    void onlyTheLeaderOrAnAdminCanChangeAGroup() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        PlayerMock member = playerWithKit("Member");
        run(leader, "squad", "invite", member.getName());
        run(member, "squad", "join", "TestGroup");
        messages(member);
        run(member, "group", "set", "head", "carved_pumpkin");
        assertTrue(received(member, "leader"));
        PlayerMock outsider = playerWithKit("Outsider");
        messages(outsider);
        run(outsider, "group", "set", "TestGroup", "head", "carved_pumpkin");
        assertTrue(received(outsider, "admin") || received(outsider, "TestGroup"));
        assertTrue(plugin.squads().squadOf(leader.getUniqueId()).orElseThrow().profile().loadout() == null);
        outsider.setOp(true);
        run(outsider, "group", "set", "TestGroup", "head", "carved_pumpkin");
        assertEquals("carved_pumpkin",
                plugin.squads().squadOf(leader.getUniqueId()).orElseThrow().profile().loadout()
                        .requirement(EquipmentStep.HELMET).token());
    }

    @Test
    void groupTabCompletionOffersSlotsAndGroupNames() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        List<String> first = command.complete(leader, new String[] {"group", "set", ""});
        assertTrue(first.containsAll(LoadoutCompletions.SLOTS) && first.contains("TestGroup"), first.toString());
        List<String> afterGroup = command.complete(leader, new String[] {"group", "set", "TestGroup", ""});
        assertEquals(LoadoutCompletions.SLOTS, afterGroup);
        List<String> items = command.complete(leader, new String[] {"group", "set", "TestGroup", "head", "carved"});
        assertTrue(items.contains("carved_pumpkin"), items.toString());
        List<String> noGroup = command.complete(leader, new String[] {"group", "set", "chest", "el"});
        assertTrue(noGroup.contains("elytra"), noGroup.toString());
        assertTrue(command.complete(leader, new String[] {"group", ""}).containsAll(List.of("set", "show", "reset")));
        assertFalse(command.complete(leader, new String[] {"squad", ""}).contains("set"), "squad keeps its own list");
    }

    @Test
    void aGroupSequenceUsesTheGroupLoadoutAndItIsIndependentFromSolo() {
        PlayerMock leader = leaderWithSquad("Boss", "TestGroup");
        PlayerMock member = playerWithKit("Member");
        run(leader, "squad", "invite", member.getName());
        run(member, "squad", "join", "TestGroup");
        run(leader, "group", "set", "head", "carved_pumpkin");
        run(leader, "group", "set", "chest", "elytra");
        for (PlayerMock p : List.of(leader, member)) {
            giveKit(p);
            p.getInventory().setItem(14, new ItemStack(Material.CARVED_PUMPKIN));
            p.getInventory().setItem(15, new ItemStack(Material.ELYTRA));
            messages(p);
        }
        run(member, "solo", "set", "head", "iron_helmet");
        assertTrue(plugin.soloLoadouts().get(leader.getUniqueId()).isEmpty(), "solo is per player");

        run(leader, "group", "start");
        tick(300);
        for (PlayerMock p : List.of(leader, member)) {
            assertEquals(Material.CARVED_PUMPKIN, worn(p, EquipmentSlot.HEAD).getType(), p.getName());
            assertEquals(Material.ELYTRA, worn(p, EquipmentSlot.CHEST).getType(), p.getName());
        }
        assertEquals("iron_helmet", plugin.soloLoadouts().get(member.getUniqueId()).orElseThrow()
                .requirement(EquipmentStep.HELMET).token(), "the group run left the solo loadout alone");
    }

    private static ItemStack worn(PlayerMock p, EquipmentSlot slot) {
        return p.getInventory().getItem(slot);
    }

    // ------------------------------------------------------------------ safety and persistence

    @Test
    void enchantmentsNamesAndDurabilityAreKept() {
        ItemStack pumpkin = new ItemStack(Material.CARVED_PUMPKIN);
        ItemMeta meta = pumpkin.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Lucky"));
        meta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
        pumpkin.setItemMeta(meta);
        ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
        axe.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        axe.editMeta(org.bukkit.inventory.meta.Damageable.class, d -> d.setDamage(12));
        solo("set", "head", "carved_pumpkin");
        solo("set", "mainhand", "netherite_axe");
        player.getInventory().setItem(9, pumpkin);
        player.getInventory().setItem(10, axe);
        messages(player);
        run(player, "solo");
        tick(200);
        assertEquals(pumpkin, worn(EquipmentSlot.HEAD));
        assertEquals(axe, worn(EquipmentSlot.HAND));
    }

    @Test
    void nothingIsCreatedDuplicatedOrDroppedAndMissingItemsAreSkipped() {
        solo("set", "head", "carved_pumpkin");
        solo("set", "chest", "elytra");
        give(0, Material.IRON_SWORD);
        give(9, Material.IRON_BOOTS);
        give(10, Material.ELYTRA);
        give(11, Material.STONE);
        int before = totalItems(player);
        messages(player);

        run(player, "solo");
        tick(200);

        assertEquals(before, totalItems(player), "same number of items");
        assertFalse(has(player, EquipmentSlot.HEAD), "no pumpkin existed, so nothing was made");
        assertEquals(Material.ELYTRA, worn(EquipmentSlot.CHEST).getType());
        assertEquals(0, server.getOnlinePlayers().stream().mapToInt(p -> 0).sum());
        assertTrue(player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty(), "nothing dropped");
    }

    @Test
    void aMissingItemIsReportedWhileTheOtherSlotsStillEquip() {
        solo("set", "head", "carved_pumpkin");
        give(9, Material.IRON_BOOTS);
        give(10, Material.IRON_LEGGINGS);
        messages(player);
        run(player, "solo");
        tick(200);
        List<String> heard = messages(player);
        assertTrue(has(player, EquipmentSlot.FEET) && has(player, EquipmentSlot.LEGS), heard.toString());
        assertTrue(heard.stream().anyMatch(m -> m.toLowerCase().contains("carved pumpkin")
                || m.toLowerCase().contains("head")), "the skipped slot is named: " + heard);
    }

    @Test
    void personalLoadoutsSurviveARestart() {
        solo("set", "head", "carved_pumpkin");
        solo("set", "chest", "elytra");
        solo("set", "offhand", "off");
        String saved = plugin.soloLoadouts().serialize();

        Path file = plugin.getDataFolder().toPath().resolve("restart-check.yml");
        SoloLoadouts fresh = new SoloLoadouts(file, plugin.getLogger());
        fresh.write(saved);
        List<String> warnings = new ArrayList<>();
        fresh.load(warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
        Loadout loaded = fresh.get(player.getUniqueId()).orElseThrow();
        Loadout original = plugin.soloLoadouts().get(player.getUniqueId()).orElseThrow();
        for (EquipmentStep step : EquipmentStep.CANONICAL) {
            assertEquals(original.requirement(step), loaded.requirement(step), step.name());
        }
        assertEquals(original.steps(), loaded.steps());
        assertEquals("carved_pumpkin", loaded.requirement(EquipmentStep.HELMET).token());
        assertEquals("elytra", loaded.requirement(EquipmentStep.CHESTPLATE).token());
        assertFalse(loaded.requirement(EquipmentStep.SHIELD).enabled());
    }

    @Test
    void theSavedFileIsWrittenWhenThePluginStops() {
        solo("set", "head", "carved_pumpkin");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
        assertTrue(plugin.getDataFolder().toPath().resolve("solo-loadouts.yml").toFile().exists());
    }

    @Test
    void aBrokenSaveFileIsReportedAndDoesNotCrash() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("broken.yml");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, "players:\n  not-a-uuid:\n    loadout:\n      head: carved_pumpkin\n  [bad");
        SoloLoadouts fresh = new SoloLoadouts(file, plugin.getLogger());
        List<String> warnings = new ArrayList<>();
        fresh.load(warnings);
        assertFalse(warnings.isEmpty());
        assertTrue(fresh.isEmpty());
    }

    @Test
    void aSequenceInProgressBlocksSoloChanges() {
        giveKit(player);
        setConfig("synchronization.solo-countdown-seconds", 3);
        run(player, "solo");
        messages(player);
        solo("set", "head", "carved_pumpkin");
        assertTrue(received(player, "Wait until"));
    }

    /** Slot names in the order the commands suggest them. */
    private static final class LoadoutCompletions {
        static final List<String> SLOTS = dev.syncequip.commands.LoadoutCommands.SLOTS;
    }
}
