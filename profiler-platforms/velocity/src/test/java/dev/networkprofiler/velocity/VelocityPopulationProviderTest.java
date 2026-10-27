package dev.networkprofiler.velocity;

import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import dev.networkprofiler.api.*;
import dev.networkprofiler.core.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class VelocityPopulationProviderTest {
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.util.function.Function<String, Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, method, args) -> answer.apply(method.getName()));
    }
    @Test void countsProxyPlayersIndependentlyOfSocketRegistryAndFollowsCurrentServer() {
        var lobby = new ServerInfo("lobby", new InetSocketAddress("127.0.0.1", 25565));
        var survival = new ServerInfo("survival", new InetSocketAddress("127.0.0.1", 25566));
        var current = new AtomicReference<ServerInfo>(lobby); UUID id = UUID.randomUUID();
        Player alice = stub(Player.class, name -> switch (name) {
            case "getUniqueId" -> id;
            case "getCurrentServer" -> Optional.ofNullable(current.get()).map(info -> stub(ServerConnection.class, key -> key.equals("getServerInfo") ? info : null));
            default -> null;
        });
        var online = new AtomicReference<List<Player>>(List.of(alice));
        ProxyServer proxy = stub(ProxyServer.class, name -> switch (name) {
            case "getAllPlayers" -> online.get();
            case "getAllServers" -> List.of(stub(RegisteredServer.class, key -> key.equals("getServerInfo") ? lobby : null), stub(RegisteredServer.class, key -> key.equals("getServerInfo") ? survival : null));
            default -> null;
        });
        try (var statistics = new PacketStatistics(StatisticsOptions.defaults())) {
            var connection = statistics.open(); connection.associate(id.toString(), "Alice", "lobby");
            statistics.open(); statistics.open(); // Pre-login sockets must not inflate real player counts.
            var provider = new VelocityPopulationProvider(proxy, statistics); var contexts = Set.of(connection.context());
            var first = provider.snapshot(contexts); assertEquals(1, first.global()); assertEquals(1, first.servers().get("lobby")); assertEquals(0, first.servers().get("survival"));
            current.set(survival); var next = provider.snapshot(contexts);
            assertEquals("survival", next.players().get(id.toString())); assertEquals("survival", next.connections().get(connection.id()));
            assertEquals("lobby", first.players().get(id.toString()));
            connection.disconnect(); current.set(null); online.set(List.of());
            var last = provider.snapshot(contexts); assertEquals(0, last.global()); assertTrue(last.players().isEmpty()); assertTrue(last.connections().isEmpty());
        }
    }
}
