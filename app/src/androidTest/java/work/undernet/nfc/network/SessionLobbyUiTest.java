package work.undernet.nfc.network;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import work.undernet.nfc.R;
import work.undernet.nfc.gui.MainActivity;
import work.undernet.nfc.network.c2s.C2S;
import work.undernet.nfc.network.data.NetworkStatus;

/** Opt-in UI and real public relay test, including a generated QR decode. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public class SessionLobbyUiTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);
    private ServerConnection peer;
    private SessionLobbyClient.Session owned;
    @Before public void idle() { ui(() -> SessionCoordinator.get(activity.getActivity()).leave()); }
    private void ui(Runnable task) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            final boolean[] ready = {false};
            ui(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(100);
        }
        fail("Expected connection UI did not appear");
    }
    private boolean text(int id, int string) {
        return ((TextView) activity.getActivity().findViewById(id)).getText().toString()
                .equals(activity.getActivity().getString(string));
    }
    private void leaveFromLibrary() {
        ui(() -> activity.getActivity().findViewById(R.id.session_another).performClick());
        ui(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                View leave = root.findViewById(R.id.session_leave);
                if (leave != null) { leave.performClick(); return; }
            }
            fail("Session library leave button missing");
        });
    }
    @After public void close() throws Exception {
        ui(() -> {
            SessionCoordinator coordinator = SessionCoordinator.get(activity.getActivity());
            coordinator.leave();
            if (owned != null) for (SavedSessionStore.Room room : coordinator.store.all())
                if (room.session.code.equals(owned.code)) coordinator.forget(room);
        });
        if (peer != null) peer.disconnect();
        Thread.sleep(700);
        if (owned != null) SessionLobbyClient.delete(owned);
    }
    @Test public void firstScreenCreateQrPartnerAndLeave() throws Exception {
        waitFor(() -> text(R.id.connection_status, R.string.connection_offline));
        ui(() -> {
            assertEquals(View.GONE, activity.getActivity().findViewById(R.id.relay_workspace).getVisibility());
            activity.getActivity().findViewById(R.id.session_create).performClick();
        });
        ui(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                EditText input = root.findViewById(R.id.session_name_input);
                if (input != null) {
                    input.setText("UI create test"); root.findViewById(android.R.id.button1).performClick(); return;
                }
            }
            fail("Create name dialog missing");
        });
        waitFor(() -> text(R.id.connection_status, R.string.connection_waiting));
        ui(() -> owned = SessionCoordinator.get(activity.getActivity()).store.active().session);
        final String[] code = {null};
        ui(() -> {
            activity.getActivity().findViewById(R.id.session_qr).performClick();
        });
        // Obtain the dialog's QR via the focused window, then decode the bitmap.
        final Bitmap[] qr = {null};
        ui(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                ImageView image = root.findViewById(R.id.invite_qr);
                if (image != null) {
                    qr[0] = ((BitmapDrawable) image.getDrawable()).getBitmap();
                    code[0] = ((TextView) root.findViewById(R.id.connection_code)).getText().toString();
                }
            }
        });
        assertNotNull(qr[0]);
        int w = qr[0].getWidth(), h = qr[0].getHeight();
        int[] pixels = new int[w*h];
        qr[0].getPixels(pixels, 0, w, 0, 0, w, h);
        String decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(w, h, pixels)))).getText();
        assertEquals(SessionLobbyClient.normalizeCode(code[0]), SessionLobbyClient.normalizeCode(decoded));
        // Back dismisses the QR dialog.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        SessionLobbyClient.Session room = SessionLobbyClient.join(code[0]);
        LiveRelayAuthTest.Events events = new LiveRelayAuthTest.Events();
        peer = new ServerConnection(NetworkManager.SERVER_HOST, NetworkManager.SERVER_PORT,
                true, room.number, room.secret).setCallback(events);
        peer.connect();
        assertEquals(NetworkStatus.CONNECTED, events.statuses.poll(15, TimeUnit.SECONDS));
        peer.send(room.number, C2S.ServerData.newBuilder().setOpcode(C2S.ServerData.Opcode.OP_SYN).build().toByteArray());
        waitFor(() -> text(R.id.connection_status, R.string.connection_ready));
        leaveFromLibrary();
        waitFor(() -> text(R.id.connection_status, R.string.connection_offline));
        assertNotNull(events.messages.poll(5, TimeUnit.SECONDS));
    }

    @Test public void joinFromFirstScreenAndValidateInput() throws Exception {
        UserTrustManager.init(activity.getActivity());
        SessionLobbyClient.Session owner = SessionLobbyClient.create();
        owned = owner;
        LiveRelayAuthTest.Events events = new LiveRelayAuthTest.Events();
        peer = new ServerConnection(NetworkManager.SERVER_HOST, NetworkManager.SERVER_PORT,
                true, owner.number, owner.secret).setCallback(events);
        peer.connect();
        assertEquals(NetworkStatus.CONNECTED, events.statuses.poll(15, TimeUnit.SECONDS));
        waitFor(() -> text(R.id.connection_status, R.string.connection_offline));
        ui(() -> activity.getActivity().findViewById(R.id.session_join).performClick());
        ui(() -> {
            for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                EditText input = root.findViewById(R.id.join_code_input);
                if (input == null) continue;
                input.setText("short");
                root.findViewById(android.R.id.button1).performClick();
                assertNotNull(input.getError());
                input.setText(owner.code);
                root.findViewById(android.R.id.button1).performClick();
                return;
            }
            fail("Join dialog missing");
        });
        waitFor(() -> text(R.id.connection_status, R.string.connection_waiting));
        // Answer the app's SYN so both screens can report a connected partner.
        assertNotNull(events.messages.poll(5, TimeUnit.SECONDS));
        peer.send(owner.number, C2S.ServerData.newBuilder().setOpcode(C2S.ServerData.Opcode.OP_ACK).build().toByteArray());
        waitFor(() -> text(R.id.connection_status, R.string.connection_ready));
        leaveFromLibrary();
        waitFor(() -> text(R.id.connection_status, R.string.connection_offline));
    }
}
