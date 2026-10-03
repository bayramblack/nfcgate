package work.undernet.nfc.network;

import static org.junit.Assert.*;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.google.android.material.navigation.NavigationView;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import work.undernet.nfc.R;
import work.undernet.nfc.gui.MainActivity;
import work.undernet.nfc.network.c2s.C2S;
import work.undernet.nfc.network.data.NetworkStatus;

/** Real relay checks: saved names, explicit leave, navigation/background and peer departure. */
public class PersistentSessionsUiTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private final List<SessionLobbyClient.Session> owned = new ArrayList<>();
    private ServerConnection peer;
    private SessionCoordinator coordinator;
    private void ui(Runnable task) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis()+20000;
        while (System.currentTimeMillis()<deadline) {
            boolean[] result = {false}; ui(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return; Thread.sleep(100);
        }
        fail("Persistent session state did not arrive");
    }
    @Before public void init() { ui(() -> { coordinator=SessionCoordinator.get(activity.getActivity()); coordinator.leave(); }); }
    @After public void close() throws Exception {
        ui(() -> {
            coordinator.leave();
            for (SavedSessionStore.Room room : coordinator.store.all())
                for (SessionLobbyClient.Session own : owned)
                    if (room.session.code.equals(own.code)) coordinator.forget(room);
        });
        if (peer != null) peer.disconnect();
        Thread.sleep(700);
        for (SessionLobbyClient.Session own : owned) SessionLobbyClient.delete(own);
    }
    @Test public void multipleNamedSessionsSwitchLeaveAndRestore() throws Exception {
        SessionLobbyClient.Session one = SessionLobbyClient.create("First test room"); owned.add(one);
        SessionLobbyClient.Session two = SessionLobbyClient.create("Second test room"); owned.add(two);
        SavedSessionStore.Room[] rooms = new SavedSessionStore.Room[2];
        ui(() -> {
            rooms[0]=coordinator.store.save(one,"one"); rooms[1]=coordinator.store.save(two,"two");
            coordinator.activate(rooms[0]);
        });
        waitFor(() -> coordinator.state().connected());
        ui(() -> {
            NavigationView nav=activity.getActivity().findViewById(R.id.main_navigation);
            nav.getMenu().performIdentifierAction(R.id.nav_about,0);
        });
        ui(() -> {
            assertEquals(one.code,coordinator.store.active().session.code);
            assertTrue(coordinator.state().connected());
            NavigationView nav=activity.getActivity().findViewById(R.id.main_navigation);
            nav.getMenu().performIdentifierAction(R.id.nav_relay,0);
            coordinator.rename(rooms[0],"Renamed test room");
        });
        assertEquals("Renamed test room",new SavedSessionStore(activity.getActivity()).active().name);
        ui(() -> coordinator.activate(rooms[1]));
        waitFor(() -> coordinator.state().connected() && coordinator.store.active().session.code.equals(two.code));
        ui(() -> coordinator.leave());
        assertNull(new SavedSessionStore(activity.getActivity()).active());
        assertTrue(new SavedSessionStore(activity.getActivity()).all().stream().anyMatch(r -> r.session.code.equals(one.code)));
        ui(() -> coordinator.activate(rooms[0]));
        waitFor(() -> coordinator.state().connected());
        assertEquals(one.code,new SavedSessionStore(activity.getActivity()).active().session.code);
    }
    @Test public void partnerLeavesBackgroundAndServiceReconnectDoNotRemoveMembership() throws Exception {
        SessionLobbyClient.Session room=SessionLobbyClient.create("Stay joined test"); owned.add(room);
        ui(() -> coordinator.activate(coordinator.store.save(room,"stay")));
        waitFor(() -> coordinator.state().connected());
        LiveRelayAuthTest.Events events=new LiveRelayAuthTest.Events();
        peer=new ServerConnection(NetworkManager.SERVER_HOST,NetworkManager.SERVER_PORT,true,room.number,room.secret).setCallback(events);
        peer.connect(); assertEquals(NetworkStatus.CONNECTED,events.statuses.poll(15,TimeUnit.SECONDS));
        peer.send(room.number,C2S.ServerData.newBuilder().setOpcode(C2S.ServerData.Opcode.OP_SYN).build().toByteArray());
        waitFor(() -> coordinator.state().phase==SessionCoordinator.Phase.READY);
        peer.disconnect(); // abrupt, without OP_FIN; server must notify only the remaining peer.
        waitFor(() -> coordinator.state().phase==SessionCoordinator.Phase.WAITING);
        assertEquals(room.code,new SavedSessionStore(activity.getActivity()).active().session.code);
        ui(() -> activity.getActivity().moveTaskToBack(true));
        Thread.sleep(1200);
        assertEquals(room.code,new SavedSessionStore(activity.getActivity()).active().session.code);
        assertTrue(coordinator.state().connected());
        activity.getActivity().startActivity(new Intent(activity.getActivity(),MainActivity.class)
                .setAction("work.undernet.nfc.OPEN_SESSION").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui(() -> coordinator.suspend()); // emulate a lost socket, then restore the selected membership.
        assertNotNull(coordinator.store.active());
        ui(() -> coordinator.retryNow());
        waitFor(() -> coordinator.state().connected());
        assertEquals(room.code,coordinator.store.active().session.code);
    }
}
