package dev.vegui.vnp.velocity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class PluginBuildVersionTest {
    @Test void generatedVersionMatchesPluginDescriptor() throws Exception {
        try (var resource = getClass().getResourceAsStream("/velocity-plugin.json")) {
            assertNotNull(resource);
            var descriptor = JsonMapper.builder().build().readTree(resource);
            assertEquals("vnp", descriptor.get("id").asString());
            assertEquals(PluginBuildVersion.VERSION, descriptor.get("version").asString());
        }
    }
}
