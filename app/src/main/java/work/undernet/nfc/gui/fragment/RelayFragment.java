package work.undernet.nfc.gui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import work.undernet.nfc.R;
import work.undernet.nfc.db.SessionLog;
import work.undernet.nfc.db.worker.LogInserter;
import work.undernet.nfc.gui.MainActivity;
import work.undernet.nfc.network.NetworkManager;
import work.undernet.nfc.network.SessionLobbyClient;
import work.undernet.nfc.network.data.NetworkStatus;
import work.undernet.nfc.nfc.modes.RelayMode;
import work.undernet.nfc.util.NfcComm;

/** Connection first, role second; the existing relay mode keeps its NFC behavior. */
public class RelayFragment extends BaseNetworkFragment {
    private View lobbyView, welcome, room, workspace, progress;
    private TextView status, hint, invite;
    private NetworkManager lobbyNetwork;
    private SessionLobbyClient.Session session;
    private ExecutorService requests;
    private final ArrayDeque<NfcComm> pending = new ArrayDeque<>();
    private int generation;
    private boolean connected, running;
    private AlertDialog joinDialog, inviteDialog;
    private EditText joinInput;
    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() != null && lobbyView != null) {
                    if (joinDialog != null) joinDialog.dismiss();
                    requestSession(result.getContents());
                }
            });

    @Override protected int getNetworkLayout() { return R.layout.fragment_relay; }
    @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                      @Nullable Bundle savedInstanceState) {
        View v = super.onCreateView(inflater, container, savedInstanceState);
        lobbyView = v;
        welcome = v.findViewById(R.id.connection_welcome);
        room = v.findViewById(R.id.connection_room);
        workspace = v.findViewById(R.id.relay_workspace);
        progress = v.findViewById(R.id.connection_progress);
        status = v.findViewById(R.id.connection_status);
        hint = v.findViewById(R.id.connection_hint);
        invite = v.findViewById(R.id.connection_code);
        requests = Executors.newSingleThreadExecutor();
        v.findViewById(R.id.session_create).setOnClickListener(view -> requestSession(null));
        v.findViewById(R.id.session_join).setOnClickListener(view -> showJoin());
        v.findViewById(R.id.session_qr).setOnClickListener(view -> showInvite());
        v.findViewById(R.id.session_share).setOnClickListener(view -> {
            if (session != null) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.code),
                    getString(R.string.connection_share)));
        });
        v.findViewById(R.id.session_leave).setOnClickListener(view -> reset());
        renderIdle(R.string.connection_intro);
        return v;
    }
    @Override public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mLogInserter = new LogInserter(getActivity(), SessionLog.SessionType.RELAY, this);
    }
    private void showJoin() {
        joinInput = new EditText(requireContext());
        joinInput.setId(R.id.join_code_input);
        joinInput.setHint(R.string.connection_code_hint);
        joinInput.setSingleLine();
        joinInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        joinInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(joinInput, new LinearLayout.LayoutParams(-1, -2));
        joinDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.connection_join).setMessage(R.string.connection_join_help).setView(box)
                .setPositiveButton(R.string.connection_connect, null)
                .setNeutralButton(R.string.connection_scan, (dialog, which) -> scanner.launch(new ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                        .setOrientationLocked(false).setPrompt(getString(R.string.connection_scan_help))))
                .setNegativeButton(R.string.connection_cancel, null).create();
        joinDialog.setOnShowListener(dialog -> joinDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        String code = SessionLobbyClient.normalizeCode(joinInput.getText().toString());
                        joinDialog.dismiss();
                        requestSession(code);
                    } catch (IllegalArgumentException e) {
                        joinInput.setError(getString(R.string.connection_bad_code));
                    }
                }));
        joinDialog.show();
    }
    private void showInvite() {
        if (session == null) return;
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        ImageView qr = new ImageView(requireContext());
        qr.setId(R.id.invite_qr);
        qr.setContentDescription(getString(R.string.connection_qr));
        try {
            qr.setImageBitmap(new BarcodeEncoder().encodeBitmap(
                    SessionLobbyClient.QR_PREFIX + session.code, BarcodeFormat.QR_CODE, 640, 640));
        } catch (com.google.zxing.WriterException e) {
            getMainActivity().showInfo(getString(R.string.connection_failed));
            return;
        }
        box.addView(qr, new LinearLayout.LayoutParams(dp(240), dp(240)));
        TextView code = new TextView(requireContext());
        code.setText(session.code);
        code.setTextSize(18);
        code.setTextIsSelectable(true);
        code.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(code);
        inviteDialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.connection_qr)
                .setMessage(R.string.connection_invite_help).setView(box)
                .setPositiveButton(R.string.connection_copy, (dialog, which) -> {
                    if (session != null) {
                        ClipboardManager clipboard = (ClipboardManager) requireContext()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("UnderNet", session.code));
                        getMainActivity().showInfo(getString(R.string.connection_copied));
                    }
                }).setNegativeButton(R.string.connection_close, null).create();
        inviteDialog.show();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void requestSession(@Nullable String code) {
        if (code != null) {
            try { code = SessionLobbyClient.normalizeCode(code); }
            catch (IllegalArgumentException e) { renderIdle(R.string.connection_bad_code); return; }
        }
        final String joinCode = code;
        final int attempt = ++generation;
        final MainActivity activity = getMainActivity();
        welcome.setVisibility(View.GONE);
        workspace.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        status.setText(R.string.connection_connecting);
        status.setTextColor(android.graphics.Color.rgb(16, 40, 59));
        hint.setText(code == null ? R.string.connection_creating : R.string.connection_joining);
        requests.execute(() -> {
            try {
                SessionLobbyClient.Session result = joinCode == null
                        ? SessionLobbyClient.create() : SessionLobbyClient.join(joinCode);
                activity.runOnUiThread(() -> {
                    if (lobbyView == null || generation != attempt) return;
                    session = result;
                    connectSession(activity, attempt, result);
                });
            } catch (IOException e) {
                int message = lobbyError(e);
                activity.runOnUiThread(() -> {
                    if (lobbyView != null && generation == attempt) renderIdle(message);
                });
            }
        });
    }
    private int lobbyError(IOException e) {
        if (e instanceof SessionLobbyClient.LobbyException) {
            switch (((SessionLobbyClient.LobbyException) e).reason) {
                case "invalid_code": return R.string.connection_expired;
                case "full": return R.string.connection_full;
                case "rate_limit": return R.string.connection_rate_limit;
                case "capacity": return R.string.connection_capacity;
            }
        }
        return R.string.connection_failed;
    }
    private void connectSession(MainActivity activity, int attempt, SessionLobbyClient.Session result) {
        lobbyNetwork = new NetworkManager(activity, new NetworkManager.Callback() {
            @Override public void onReceive(NfcComm data) {
                activity.runOnUiThread(() -> {
                    if (lobbyView == null || generation != attempt) return;
                    if (running) getNfc().handleData(true, data);
                    else if (pending.size() < 16) pending.add(data);
                });
            }
            @Override public void onNetworkStatus(NetworkStatus value) {
                activity.runOnUiThread(() -> {
                    if (lobbyView != null && generation == attempt) updateConnection(value);
                });
            }
        });
        lobbyNetwork.connect(result.number, result.secret);
    }
    private void updateConnection(NetworkStatus value) {
        switch (value) {
            case CONNECTED:
                connected = true;
                status.setTextColor(android.graphics.Color.rgb(0, 105, 92));
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .putString("session", Integer.toString(session.number))
                        .putString("session_secret", session.secret).putBoolean("tls", true).apply();
                progress.setVisibility(View.GONE);
                room.setVisibility(View.VISIBLE);
                workspace.setVisibility(View.VISIBLE);
                invite.setText(session.code);
                status.setText(R.string.connection_waiting);
                hint.setText(R.string.connection_invite_help);
                break;
            case PARTNER_CONNECT:
                status.setText(R.string.connection_ready);
                hint.setText(running ? R.string.connection_active : R.string.connection_choose_role);
                break;
            case PARTNER_LEFT:
                reset(); hint.setText(R.string.connection_partner_left); return;
            case ERROR_AUTH:
                reset(); hint.setText(R.string.connection_full); return;
            case ERROR:
            case ERROR_TLS:
            case ERROR_TLS_CERT_UNKNOWN:
            case ERROR_TLS_CERT_UNTRUSTED:
                reset(); hint.setText(R.string.connection_failed); return;
            default: break;
        }
    }
    private void renderIdle(int message) {
        connected = running = false;
        welcome.setVisibility(View.VISIBLE);
        room.setVisibility(View.GONE);
        workspace.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        status.setText(R.string.connection_offline);
        status.setTextColor(android.graphics.Color.rgb(16, 40, 59));
        hint.setText(message);
        setSelectorVisible(true);
        setTagWaitVisible(false, false);
    }
    @Override protected void reset() {
        ++generation;
        if (lobbyNetwork != null) { lobbyNetwork.disconnect(); lobbyNetwork = null; }
        session = null;
        pending.clear();
        super.reset();
        getMainActivity().findViewById(R.id.banner).setVisibility(View.GONE);
        if (lobbyView != null) renderIdle(R.string.connection_intro);
    }
    @Override protected void onSelect(boolean reader) {
        if (!connected || !checkNetwork()) return;
        running = true;
        setSelectorVisible(false);
        setTagWaitVisible(true, !reader);
        getNfc().startMode(new UIRelayMode(reader));
        hint.setText(reader ? R.string.connection_reader_help : R.string.connection_tag_help);
        mTagWaitingText.setText(reader ? R.string.connection_reader_help : R.string.connection_tag_help);
        while (!pending.isEmpty()) getNfc().handleData(true, pending.remove());
    }
    @Override public void onDestroyView() {
        reset();
        if (joinDialog != null) joinDialog.dismiss();
        if (inviteDialog != null) inviteDialog.dismiss();
        requests.shutdownNow();
        lobbyView = null;
        super.onDestroyView();
    }
    class UIRelayMode extends RelayMode {
        UIRelayMode(boolean reader) {
            super(reader);
            // The authenticated lobby connection is also the relay connection.
            mOnline = false;
        }
        @Override protected void toNetwork(NfcComm data) {
            if (lobbyNetwork != null) lobbyNetwork.send(data);
        }
        @Override public void onData(boolean isForeign, NfcComm data) {
            mLogInserter.log(data);
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (lobbyView != null) setTagWaitVisible(false, false);
            });
            super.onData(isForeign, data);
        }
    }
}
