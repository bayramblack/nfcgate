package work.undernet.nfc.network;

import android.content.SharedPreferences;
import android.content.Context;
import androidx.preference.PreferenceManager;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import work.undernet.nfc.network.c2s.C2S;
import work.undernet.nfc.network.data.NetworkStatus;
import work.undernet.nfc.util.NfcComm;

import static work.undernet.nfc.network.c2s.C2S.ServerData.Opcode;

public class NetworkManager implements ServerConnection.Callback {
    private static final String TAG = "NetworkManager";

    public interface Callback {
        void onReceive(NfcComm data);
        void onNetworkStatus(NetworkStatus status);
    }

    // references
    private final Context mActivity;
    private boolean keepConnectionOnPartnerLeft;
    private volatile long lastPong;
    private ServerConnection mConnection;
    private final Callback mCallback;

    // preference data
    public static final String SERVER_HOST = "relay.undernet.work";
    public static final int SERVER_PORT = 5566;
    private int mSessionNumber;

    public NetworkManager(Context activity, Callback cb) {
        mActivity = activity.getApplicationContext();
        mCallback = cb;
    }

    public NetworkManager keepConnectionOnPartnerLeft() {
        keepConnectionOnPartnerLeft = true;
        return this;
    }

    public void ping() {
        if (mConnection != null) mConnection.send(mSessionNumber,
                "UNDERNET-PING/1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public long getLastPong() { return lastPong; }

    public void connect() {
        // read fresh preference data
        loadPreferenceData();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        String sessionSecret = prefs.getString("session_secret", "");
        connect(mSessionNumber, sessionSecret);
    }

    public void connect(int session, String sessionSecret) {
        if (mConnection != null) disconnect();
        mSessionNumber = session;
        mConnection = new ServerConnection(SERVER_HOST, SERVER_PORT, true,
                mSessionNumber, sessionSecret)
                .setCallback(this)
                .connect();

        // queue initial handshake message
        sendServer(Opcode.OP_SYN, null);
    }

    public void disconnect() {
        if (mConnection != null) {
            sendServer(Opcode.OP_FIN, null);
            mConnection.sync();
            mConnection.disconnect();
        }
    }

    public void send(NfcComm data) {
        // queue data message
        sendServer(Opcode.OP_PSH, data.toByteArray());
    }

    @Override
    public void onReceive(byte[] data) {
        if (java.util.Arrays.equals(data, "UNDERNET-PONG/1".getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            lastPong = android.os.SystemClock.elapsedRealtime();
            return;
        }
        final C2S.ServerData serverData;
        try {
            serverData = C2S.ServerData.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Message parsing failed", e);
            return;
        }

        Log.v(TAG, "Got message "+serverData.getOpcode().toString());
        switch (serverData.getOpcode()) {
            case OP_SYN:
                // empty syn message indicates our peer has just connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);
                // return ack
                sendServer(Opcode.OP_ACK, null);

                break;
            case OP_ACK:
                // empty ack message indicates our peer was already connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);

                break;
            case OP_FIN:
                // our peer has disconnected
                onNetworkStatus(NetworkStatus.PARTNER_LEFT);
                if (!keepConnectionOnPartnerLeft) mConnection.disconnect();

                break;
            case OP_PSH:
                // pass data to callback
                mCallback.onReceive(new NfcComm(serverData.getData().toByteArray()));

                break;
        }
    }

    @Override
    public void onNetworkStatus(NetworkStatus status) {
        mCallback.onNetworkStatus(status);
    }

    private void loadPreferenceData() {
        // read data from shared prefs
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        try {
            mSessionNumber = Integer.parseInt(prefs.getString("session", "0"));
        } catch (NumberFormatException e) {
            mSessionNumber = 0;
        }
    }

    private void sendServer(Opcode opcode, byte[] data) {
        mConnection.send(mSessionNumber,
                C2S.ServerData.newBuilder()
                    .setOpcode(opcode)
                    .setData(data == null ? ByteString.EMPTY : ByteString.copyFrom(data))
                    .build()
                    .toByteArray());
    }
}
