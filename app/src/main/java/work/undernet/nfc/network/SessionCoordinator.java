package work.undernet.nfc.network;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.preference.PreferenceManager;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import work.undernet.nfc.R;
import work.undernet.nfc.network.data.NetworkStatus;
import work.undernet.nfc.util.NfcComm;

/** Owns membership independently of a screen, and one active relay connection. Main-thread API. */
public final class SessionCoordinator {
    public enum Phase { IDLE, REQUESTING, CONNECTING, WAITING, READY, RETRYING, UNAVAILABLE }
    public static final class State {
        public final SavedSessionStore.Room room;
        public final Phase phase;
        public final int message;
        State(SavedSessionStore.Room room, Phase phase, int message) {
            this.room = room; this.phase = phase; this.message = message;
        }
        public boolean connected() { return phase == Phase.WAITING || phase == Phase.READY; }
    }
    public interface DataListener { void onData(NfcComm data); }
    private static SessionCoordinator instance;
    public static synchronized SessionCoordinator get(Context context) {
        if (instance == null) instance = new SessionCoordinator(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    public final SavedSessionStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final MutableLiveData<State> states = new MutableLiveData<>();
    private NetworkManager network;
    private DataListener listener;
    private NfcComm pendingInitial;
    private int generation, failures;
    private boolean busy;
    private long connectedAt;
    private SessionCoordinator(Context context) {
        this.context = context;
        store = new SavedSessionStore(context);
        states.setValue(new State(store.active(), store.active() == null ? Phase.IDLE : Phase.RETRYING, 0));
    }
    public LiveData<State> states() { return states; }
    public State state() { return states.getValue(); }
    private void publish(Phase phase, int message) { states.setValue(new State(store.active(), phase, message)); }
    private void startService() {
        ContextCompat.startForegroundService(context, new Intent(context, SessionConnectionService.class));
    }
    public void restore() { if (store.active() != null && !busy) startService(); }
    public void create(String name) { request(null, name); }
    public void join(String code) { request(SessionLobbyClient.normalizeCode(code), ""); }
    private void request(String code, String name) {
        if (busy) return;
        busy = true;
        stopConnection();
        store.activate(null);
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("session_secret").remove("session").apply();
        publish(Phase.REQUESTING, code == null ? R.string.connection_creating : R.string.connection_joining);
        // Foreground protection begins while the user is visibly initiating the action.
        startService();
        final int attempt = generation;
        requests.execute(() -> {
            try {
                SessionLobbyClient.Session result = code == null ? SessionLobbyClient.create(name) : SessionLobbyClient.join(code);
                main.post(() -> {
                    busy = false;
                    SavedSessionStore.Room room = store.save(result, context.getString(R.string.sessions_default));
                    if (attempt != generation) { publish(state().phase, 0); return; }
                    activate(room);
                });
            } catch (IOException | IllegalArgumentException e) {
                main.post(() -> {
                    busy = false;
                    if (attempt == generation) publish(store.active() == null ? Phase.IDLE : state().phase, error(e));
                });
            }
        });
    }
    public void activate(SavedSessionStore.Room room) {
        stopConnection();
        store.activate(room);
        failures = 0;
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString("session", Integer.toString(room.session.number))
                .putString("session_secret", room.session.secret).putBoolean("tls", true).apply();
        publish(Phase.CONNECTING, 0);
        startService();
    }
    public void leave() {
        stopConnection();
        store.activate(null);
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("session_secret").remove("session").apply();
        publish(Phase.IDLE, 0);
        context.stopService(new Intent(context, SessionConnectionService.class));
    }
    public void rename(SavedSessionStore.Room room, String name) {
        store.rename(room, name); publish(state().phase, state().message);
    }
    public void forget(SavedSessionStore.Room room) {
        if (store.active() != null && store.active().session.code.equals(room.session.code)) leave();
        store.forget(room); publish(state().phase, 0);
    }
    public void delete(SavedSessionStore.Room room) {
        requests.execute(() -> {
            try { SessionLobbyClient.delete(room.session); main.post(() -> forget(room)); }
            catch (IOException e) { main.post(() -> publish(state().phase, error(e))); }
        });
    }
    public void connectActive() {
        SavedSessionStore.Room room = store.active();
        if (room == null || network != null || busy) return;
        main.removeCallbacks(retry);
        final int attempt = ++generation;
        publish(Phase.CONNECTING, 0);
        network = new NetworkManager(context, new NetworkManager.Callback() {
            public void onReceive(NfcComm data) { main.post(() -> {
                if (attempt != generation) return;
                if (listener != null) listener.onData(data);
                else if (data.isInitial()) pendingInitial = data;
            }); }
            public void onNetworkStatus(NetworkStatus status) { main.post(() -> {
                if (attempt != generation) return;
                switch (status) {
                    case CONNECTED:
                        failures = 0; connectedAt = SystemClock.elapsedRealtime();
                        publish(Phase.WAITING, 0); main.postDelayed(heartbeat, 25000); break;
                    case PARTNER_CONNECT: publish(Phase.READY, 0); break;
                    case PARTNER_LEFT: pendingInitial = null; publish(Phase.WAITING, R.string.sessions_partner_left); break;
                    case ERROR_AUTH:
                        stopConnection(); publish(Phase.UNAVAILABLE, R.string.sessions_unavailable); break;
                    case ERROR: case ERROR_TLS: case ERROR_TLS_CERT_UNKNOWN: case ERROR_TLS_CERT_UNTRUSTED:
                        reconnect(); break;
                    default: break;
                }
            }); }
        }).keepConnectionOnPartnerLeft();
        network.connect(room.session.number, room.session.secret);
    }
    private final Runnable retry = this::connectActive;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (network == null || !state().connected()) return;
            long last = Math.max(connectedAt, network.getLastPong());
            if (SystemClock.elapsedRealtime() - last >= 75000) { reconnect(); return; }
            network.ping(); main.postDelayed(this, 25000);
        }
    };
    private void reconnect() {
        stopConnection();
        if (store.active() == null) return;
        publish(Phase.RETRYING, R.string.sessions_reconnecting);
        main.postDelayed(retry, Math.min(15000, 1000L << Math.min(failures++, 4)));
    }
    public void retryNow() { stopConnection(); startService(); connectActive(); }
    public void suspend() { stopConnection(); if (store.active() != null) publish(Phase.RETRYING, 0); }
    private void stopConnection() {
        ++generation; main.removeCallbacks(retry); main.removeCallbacks(heartbeat);
        pendingInitial = null; listener = null;
        if (network != null) { NetworkManager old = network; network = null; old.disconnect(); }
    }
    public boolean isBusy() { return busy; }
    public void setDataListener(DataListener value) {
        listener = value;
        if (value != null && pendingInitial != null) {
            NfcComm data = pendingInitial; pendingInitial = null; value.onData(data);
        }
    }
    public void send(NfcComm data) { if (network != null && state().connected()) network.send(data); }
    private int error(Exception e) {
        if (e instanceof SessionLobbyClient.LobbyException) {
            switch (((SessionLobbyClient.LobbyException)e).reason) {
                case "full": return R.string.connection_full;
                case "invalid_code": return R.string.connection_expired;
                case "rate_limit": return R.string.connection_rate_limit;
                case "capacity": return R.string.connection_capacity;
                case "forbidden": return R.string.sessions_forbidden;
            }
        }
        return R.string.connection_failed;
    }
}
