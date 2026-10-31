package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CommandTest extends PluginTestBase {

    @Test
    void createInviteJoinListLeave() {
        PlayerMock leader = server.addPlayer("Leader");
        PlayerMock alice = server.addPlayer("Alice");

        run(leader, "squad", "create");
        assertTrue(received(leader, "Squad created"));

        run(leader, "squad", "invite", "Alice");
        assertTrue(received(leader, "Invited Alice"));
        assertTrue(received(alice, "Leader invited you"));

        run(alice, "squad", "join", "Leader");
        assertTrue(received(leader, "Alice joined the squad"));

        run(alice, "squad", "list");
        List<String> list = messages(alice);
        assertTrue(list.stream().anyMatch(m -> m.contains("Leader") && m.contains("(leader)")));
        assertTrue(list.stream().anyMatch(m -> m.contains("Alice")));

        run(alice, "squad", "leave");
        assertTrue(received(alice, "You left the squad"));
        assertTrue(received(leader, "Alice left the squad"));
    }

    @Test
    void joiningWithoutInviteIsRejected() {
        PlayerMock leader = server.addPlayer("Leader");
        PlayerMock alice = server.addPlayer("Alice");
        run(leader, "squad", "create");
        run(alice, "squad", "join", "Leader");
        assertTrue(received(alice, "no valid invite"));
    }

    @Test
    void unknownPlayersAreReported() {
        PlayerMock leader = server.addPlayer("Leader");
        run(leader, "squad", "create");
        run(leader, "squad", "invite", "Nobody");
        assertTrue(received(leader, "Nobody is not online"));
        run(leader, "squad", "invite");
        assertTrue(received(leader, "Usage"));
    }

    @Test
    void onlyLeaderInvitesAndTransfers() {
        PlayerMock leader = server.addPlayer("Leader");
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        run(leader, "squad", "create");
        run(leader, "squad", "invite", "Alice");
        run(alice, "squad", "join", "Leader");
        messages(alice);

        run(alice, "squad", "invite", "Bob");
        assertTrue(received(alice, "Only the squad leader"));

        run(leader, "squad", "transfer", "Bob");
        assertTrue(received(leader, "Bob is not in your squad"));

        run(leader, "squad", "transfer", "Alice");
        assertTrue(received(leader, "Alice is now the squad leader"));
        assertTrue(messages(bob).isEmpty(), "outsiders are not notified");
        assertTrue(plugin.squads().squadOf(alice.getUniqueId()).orElseThrow().isLeader(alice.getUniqueId()));
    }

    @Test
    void maxSquadSizeIsEnforcedByCommands() {
        setConfig("synchronization.max-squad-size", 2);
        PlayerMock leader = server.addPlayer("Leader");
        PlayerMock alice = server.addPlayer("Alice");
        server.addPlayer("Bob");
        run(leader, "squad", "create");
        run(leader, "squad", "invite", "Alice");
        run(alice, "squad", "join", "Leader");
        messages(leader);
        run(leader, "squad", "invite", "Bob");
        assertTrue(received(leader, "squad is full"));
    }

    @Test
    void squadCommandsRequirePermission() {
        PlayerMock player = server.addPlayer("Steve");
        PermissionAttachment attachment = player.addAttachment(plugin);
        attachment.setPermission("syncequip.squad", false);
        attachment.setPermission("syncequip.use", false);

        run(player, "squad", "create");
        assertTrue(received(player, "do not have permission"));
        run(player, "solo");
        assertTrue(received(player, "do not have permission"));
        assertTrue(plugin.squads().squadOf(player.getUniqueId()).isEmpty());
    }

    @Test
    void reloadRequiresPermission() {
        PlayerMock player = server.addPlayer("Steve");
        run(player, "reload");
        assertTrue(received(player, "do not have permission"));

        player.setOp(true);
        run(player, "reload");
        assertTrue(received(player, "Configuration reloaded"));
    }

    @Test
    void reloadReportsInvalidValues() {
        PlayerMock admin = server.addPlayer("Admin");
        admin.setOp(true);
        plugin.getConfig().set("equipment.interval-ticks", 99);
        plugin.saveConfig();
        run(admin, "reload");
        List<String> output = messages(admin);
        assertTrue(output.stream().anyMatch(m -> m.contains("1 warning")));
        assertEquals(10, plugin.settings().intervalTicks());
    }

    @Test
    void consoleCannotRunPlayerCommands() {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();
        command.handle(console, new String[]{"solo"});
        assertTrue(console.nextMessage().contains("Only players"));
    }

    @Test
    void unknownSubcommandAndHelp() {
        PlayerMock player = server.addPlayer("Steve");
        run(player, "dance");
        assertTrue(received(player, "Unknown subcommand dance"));
        run(player);
        List<String> help = messages(player);
        assertTrue(help.stream().anyMatch(m -> m.contains("/syncequip squad start")));
    }

    @Test
    void settingsShowsCurrentValues() {
        PlayerMock player = server.addPlayer("Steve");
        run(player, "settings");
        List<String> output = messages(player);
        assertTrue(output.stream().anyMatch(m -> m.contains("Interval") && m.contains("2 ticks")));
        assertTrue(output.stream().anyMatch(m -> m.contains("Boots, Leggings, Chestplate, Helmet, Shield")));
    }

    @Test
    void tabCompletion() {
        PlayerMock leader = server.addPlayer("Leader");
        server.addPlayer("Alice");
        assertEquals(List.of("solo", "squad", "settings"), command.complete(leader, new String[]{"s"}));
        assertFalse(command.complete(leader, new String[]{""}).contains("reload"), "hidden without permission");
        assertEquals(List.of("start"), command.complete(leader, new String[]{"squad", "st"}));
        assertEquals(List.of("Alice"), command.complete(leader, new String[]{"squad", "invite", "a"}));
        assertEquals(List.of(), command.complete(leader, new String[]{"squad", "join", ""}), "no squad leaders yet");
    }

    @Test
    void soloCooldownEnforcedAndBypassable() {
        PlayerMock player = playerWithKit("Steve");
        run(player, "solo");
        tick(15);
        // Take the armor off again and hand out a fresh kit.
        for (EquipmentSlot slot : SEQUENCE_SLOTS) {
            player.getInventory().setItem(slot, null);
        }
        giveKit(player);
        messages(player);

        run(player, "solo");
        assertTrue(received(player, "on cooldown"));

        player.setOp(true);
        run(player, "solo");
        tick(15);
        assertEquals(5, countEquipped(player));
    }
}
