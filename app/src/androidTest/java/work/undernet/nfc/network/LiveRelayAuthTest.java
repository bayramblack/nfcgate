package work.undernet.nfc.network;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import work.undernet.nfc.network.data.NetworkStatus;

/** Opt-in live checks; credentials are supplied in the target app's private files. */
public class LiveRelayAuthTest {
    @Rule public Timeout timeout = Timeout.seconds(40);
    private JSONObject secrets;
    private final List<ServerConnection> connections = new ArrayList<>();

    static class Events implements ServerConnection.Callback {
        final BlockingQueue<NetworkStatus> statuses = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        public void onReceive(byte[] data) { messages.add(data); }
        public void onNetworkStatus(NetworkStatus status) { statuses.add(status); }
    }

    @Before public void setUp() throws Exception {
        UserTrustManager.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .openFileInput("relay-test-secrets.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, length);
            secrets = new JSONObject(bytes.toString("UTF-8"));
        }
    }

    @After public void tearDown() throws Exception {
        for (ServerConnection connection : connections) connection.disconnect();
        // Allow the server to release slots before the next test begins.
        Thread.sleep(700);
    }

    private ServerConnection connect(int session, String secret, Events events) {
        // TLS checkbox false deliberately: supplying a secret must force TLS.
        ServerConnection connection = new ServerConnection("relay.undernet.work", 5566,
                false, session, secret).setCallback(events);
        connections.add(connection);
        connection.connect();
        return connection;
    }

    private Events accepted(int session) throws Exception {
        Events events = new Events();
        connect(session, secrets.getString(Integer.toString(session)), events);
        assertEquals(NetworkStatus.CONNECTED, events.statuses.poll(15, TimeUnit.SECONDS));
        return events;
    }

    @Test public void validCredentialsRelayAndReleaseSlots() throws Exception {
        Events a = accepted(1), b = accepted(1), other = accepted(2);
        byte[] forward = "android-reader".getBytes(StandardCharsets.UTF_8);
        byte[] reply = "android-tag".getBytes(StandardCharsets.UTF_8);
        connections.get(0).send(1, forward);
        assertArrayEquals(forward, b.messages.poll(5, TimeUnit.SECONDS));
        connections.get(1).send(1, reply);
        assertArrayEquals(reply, a.messages.poll(5, TimeUnit.SECONDS));
        assertNull(other.messages.poll(250, TimeUnit.MILLISECONDS));
        Events third = new Events();
        connect(1, secrets.getString("1"), third);
        assertEquals(NetworkStatus.ERROR_AUTH, third.statuses.poll(15, TimeUnit.SECONDS));
        connections.get(0).disconnect();
        Thread.sleep(700);
        accepted(1); // A real app disconnect must free its authenticated slot.
    }

    @Test public void wrongAndMissingSecretsCannotEnter() throws Exception {
        Events wrong = new Events();
        connect(1, "wrong-test-credential-0000000000", wrong);
        assertEquals(NetworkStatus.ERROR_AUTH, wrong.statuses.poll(15, TimeUnit.SECONDS));
        Events unknown = new Events();
        connect(99, secrets.getString("1"), unknown);
        assertEquals(NetworkStatus.ERROR_AUTH, unknown.statuses.poll(15, TimeUnit.SECONDS));
        // Empty secret with TLS can establish a socket, but the server must reject relay frames.
        Events legacy = new Events();
        ServerConnection connection = new ServerConnection("relay.undernet.work", 5566, true)
                .setCallback(legacy);
        connections.add(connection);
        connection.connect();
        assertEquals(NetworkStatus.CONNECTED, legacy.statuses.poll(15, TimeUnit.SECONDS));
        connection.send(1, new byte[] {8, 1});
        assertEquals(NetworkStatus.ERROR, legacy.statuses.poll(15, TimeUnit.SECONDS));
        assertNull(legacy.messages.poll(250, TimeUnit.MILLISECONDS));
    }

    @Test public void authenticatedClientCannotSwitchSessions() throws Exception {
        Events a = accepted(1), b = accepted(2);
        connections.get(0).send(2, "blocked-switch".getBytes(StandardCharsets.UTF_8));
        assertEquals(NetworkStatus.ERROR, a.statuses.poll(10, TimeUnit.SECONDS));
        assertNull(b.messages.poll(250, TimeUnit.MILLISECONDS));
    }
}
