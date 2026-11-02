package dev.syncequip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MessagesTest extends PluginTestBase {

    @Test
    void keysMissingFromAnOlderMessagesFileFallBackToTheBundledText() throws Exception {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        old.set("demo", null);
        old.set("squad.created", null);
        old.save(file);
        assertFalse(YamlConfiguration.loadConfiguration(file).isSet("demo.spawned"), "test setup removed the section");
        plugin.messages().load();

        PlayerMock player = server.addPlayer("Steve");
        run(player, "squad", "create");

        assertTrue(received(player, "Squad created"));
        assertFalse(plugin.messages().raw("demo.spawned").contains("Missing message"));
    }
}
