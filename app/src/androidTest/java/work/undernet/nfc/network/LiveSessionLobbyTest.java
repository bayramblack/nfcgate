package work.undernet.nfc.network;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import work.undernet.nfc.network.data.NetworkStatus;

/** Opt-in test against the deployed TLS invitation service; no static secrets needed. */
public class LiveSessionLobbyTest {
    private final List<ServerConnection> connections = new ArrayList<>();
    @Before public void init() {
        UserTrustManager.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }
    @After public void close() throws Exception {
        for (ServerConnection connection : connections) connection.disconnect();
        Thread.sleep(700);
    }
    private LiveRelayAuthTest.Events connect(SessionLobbyClient.Session room) throws Exception {
        LiveRelayAuthTest.Events events = new LiveRelayAuthTest.Events();
        ServerConnection connection = new ServerConnection(NetworkManager.SERVER_HOST,
                NetworkManager.SERVER_PORT, false, room.number, room.secret).setCallback(events);
        connections.add(connection);
        connection.connect();
        assertEquals(NetworkStatus.CONNECTED, events.statuses.poll(15, TimeUnit.SECONDS));
        return events;
    }
    @Test public void createJoinRelayAndFullRoom() throws Exception {
        SessionLobbyClient.Session owner = SessionLobbyClient.create();
        LiveRelayAuthTest.Events a = connect(owner);
        SessionLobbyClient.Session guest = SessionLobbyClient.join(owner.code.toLowerCase());
        assertEquals(owner.number, guest.number);
        assertEquals(owner.secret, guest.secret);
        LiveRelayAuthTest.Events b = connect(guest);
        byte[] payload = new byte[]{8, 1};
        connections.get(0).send(owner.number, payload);
        assertArrayEquals(payload, b.messages.poll(5, TimeUnit.SECONDS));
        connections.get(1).send(owner.number, payload);
        assertArrayEquals(payload, a.messages.poll(5, TimeUnit.SECONDS));
        try { SessionLobbyClient.join(owner.code); fail("Full room accepted another invitation request"); }
        catch (SessionLobbyClient.LobbyException e) { assertEquals("full", e.reason); }
    }
    @Test public void invalidInviteAndQrParsing() throws Exception {
        try { SessionLobbyClient.join("2222-2222-2222-2222"); fail("Invalid invite accepted"); }
        catch (SessionLobbyClient.LobbyException e) { assertEquals("invalid_code", e.reason); }
        assertEquals("ABCDEFGHJKLMNPQR", SessionLobbyClient.normalizeCode(
                "undernet:join:ABCD-EFGH-JKLM-NPQR"));
        for (String invalid : new String[]{"1234", "https://other-server/room", "0000-0000-0000-0000"}) {
            try { SessionLobbyClient.normalizeCode(invalid); fail("Unsafe payload accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
