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
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import work.undernet.nfc.R;
import work.undernet.nfc.db.SessionLog;
import work.undernet.nfc.db.worker.LogInserter;
import work.undernet.nfc.network.SessionLobbyClient;
import work.undernet.nfc.network.SessionCoordinator;
import work.undernet.nfc.network.SavedSessionStore;
import android.widget.Button;
import work.undernet.nfc.nfc.modes.RelayMode;
import work.undernet.nfc.util.NfcComm;

/** Connection first, role second; the existing relay mode keeps its NFC behavior. */
public class RelayFragment extends BaseNetworkFragment {
    private View lobbyView, welcome, room, workspace, progress;
    private TextView status, hint;
    private LinearLayout libraryList;
    private SessionLobbyClient.Session session;
    private SessionCoordinator coordinator;
    private boolean running, selectedReader, roleChosen;
    private String displayedCode = "";
    private AlertDialog joinDialog, inviteDialog, libraryDialog;
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
        coordinator = SessionCoordinator.get(requireContext());
        v.findViewById(R.id.session_create).setOnClickListener(view -> showCreate());
        v.findViewById(R.id.session_join).setOnClickListener(view -> showJoin());
        v.findViewById(R.id.session_qr).setOnClickListener(view -> showInvite());
        v.findViewById(R.id.session_retry).setOnClickListener(view -> coordinator.retryNow());
        v.findViewById(R.id.session_another).setOnClickListener(view -> showSessionLibrary());
        v.findViewById(R.id.nfc_start).setOnClickListener(view -> startSelectedRole());
        v.findViewById(R.id.nfc_change_role).setOnClickListener(view -> {
            stopRole();
            renderState(coordinator.state());
        });
        renderState(coordinator.state());
        return v;
    }
    @Override public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mLogInserter = new LogInserter(getActivity(), SessionLog.SessionType.RELAY, this);
        coordinator.states().observe(getViewLifecycleOwner(), this::renderState);
    }
    private void showJoin() {
        joinInput = new EditText(requireContext());
        joinInput.setId(R.id.join_code_input);
        joinInput.setHint(R.string.connection_code_hint);
        joinInput.setSingleLine();
        joinInput.setTextDirection(View.TEXT_DIRECTION_LTR);
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
        code.setId(R.id.connection_code);
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
                }).setNeutralButton(R.string.connection_share, (dialog, which) -> {
                    if (session != null) startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.code),
                            getString(R.string.connection_share)));
                }).setNegativeButton(R.string.connection_close, null).create();
        inviteDialog.show();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void requestSession(@Nullable String code) {
        try { coordinator.join(code); }
        catch (IllegalArgumentException e) { hint.setText(R.string.connection_bad_code); }
    }
    private void stopRole() {
        if (coordinator != null) coordinator.setDataListener(null);
        getNfc().stopMode();
        running = false;
        roleChosen = false;
        setSelectorVisible(true);
        setTagWaitVisible(false, false);
        if (mLogInserter != null) mLogInserter.reset();
    }
    @Override protected void reset() {
        // Toolbar refresh retries the selected session; it is never an implicit leave.
        stopRole();
        if (coordinator != null && lobbyView != null) coordinator.retryNow();
        if (getMainActivity() != null) getMainActivity().findViewById(R.id.banner).setVisibility(View.GONE);
    }
    private void renderState(SessionCoordinator.State state) {
        if (lobbyView == null || state == null) return;
        String code = state.room == null ? "" : state.room.session.code;
        if (!code.equals(displayedCode) || !state.connected() || state.message == R.string.sessions_partner_left) stopRole();
        displayedCode = code;
        session = state.room == null ? null : state.room.session;
        boolean active = session != null;
        welcome.setVisibility(!active && state.phase != SessionCoordinator.Phase.REQUESTING ? View.VISIBLE : View.GONE);
        room.setVisibility(active ? View.VISIBLE : View.GONE);
        workspace.setVisibility(active && state.connected() ? View.VISIBLE : View.GONE);
        progress.setVisibility(state.phase == SessionCoordinator.Phase.REQUESTING || state.phase == SessionCoordinator.Phase.CONNECTING
                || state.phase == SessionCoordinator.Phase.RETRYING ? View.VISIBLE : View.GONE);
        lobbyView.findViewById(R.id.session_retry).setVisibility(state.phase == SessionCoordinator.Phase.UNAVAILABLE ? View.VISIBLE : View.GONE);
        lobbyView.<Button>findViewById(R.id.session_another).setText(active ? R.string.wizard_change_session : R.string.sessions_title);
        lobbyView.findViewById(R.id.session_another).setEnabled(state.phase != SessionCoordinator.Phase.REQUESTING);
        status.setText(state.phase == SessionCoordinator.Phase.READY ? R.string.connection_ready
                : state.phase == SessionCoordinator.Phase.WAITING ? R.string.connection_waiting
                : state.phase == SessionCoordinator.Phase.IDLE ? R.string.connection_offline
                : state.phase == SessionCoordinator.Phase.UNAVAILABLE ? R.string.sessions_unavailable
                : R.string.connection_connecting);
        status.setTextColor(state.connected() ? android.graphics.Color.rgb(110, 220, 208) : android.graphics.Color.WHITE);
        hint.setText(state.message != 0 ? state.message : state.phase == SessionCoordinator.Phase.REQUESTING ? R.string.connection_creating
                : state.phase == SessionCoordinator.Phase.RETRYING ? R.string.sessions_reconnecting
                : state.phase == SessionCoordinator.Phase.READY ? R.string.connection_choose_role
                : active ? R.string.connection_choose_role : R.string.connection_intro);
        if (active) {
            lobbyView.<TextView>findViewById(R.id.active_session_name).setText(state.room.name);
            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.content.SharedPreferences ui = requireContext().getSharedPreferences("session_ui", android.content.Context.MODE_PRIVATE);
                if (!ui.getBoolean("notification_requested", false)) {
                    ui.edit().putBoolean("notification_requested", true).apply();
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 51);
                }
            }
        }
        renderWizard();
        renderLibrary();
    }
    private void renderWizard() {
        if (lobbyView == null) return;
        boolean active = session != null;
        lobbyView.<TextView>findViewById(R.id.wizard_step).setText(!active ? R.string.wizard_step_session
                : roleChosen ? R.string.wizard_step_nfc : R.string.wizard_step_role);
        // Each step owns its content. Session details and saved rooms live in dialogs.
        hint.setVisibility(!active || !coordinator.state().connected() ? View.VISIBLE : View.GONE);
        setSelectorVisible(!roleChosen);
        mTagWaiting.setVisibility(roleChosen ? View.VISIBLE : View.GONE);
        if (roleChosen) {
            lobbyView.<ImageView>findViewById(R.id.nfc_role_icon).setImageResource(
                    selectedReader ? R.drawable.ic_pos_card : R.drawable.ic_pos_terminal);
            lobbyView.<TextView>findViewById(R.id.nfc_role_title).setText(
                    selectedReader ? R.string.connection_reader : R.string.connection_tag);
            mTagWaitingText.setText(selectedReader ? R.string.connection_reader_description : R.string.connection_tag_description);
            lobbyView.<TextView>findViewById(R.id.nfc_activity_status).setText(
                    running ? R.string.wizard_nfc_running : R.string.wizard_nfc_ready);
            lobbyView.findViewById(R.id.nfc_start).setVisibility(running ? View.GONE : View.VISIBLE);
        }
    }
    private void showSessionLibrary() {
        if (libraryDialog != null && libraryDialog.isShowing()) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_session_library, null);
        libraryList = content.findViewById(R.id.saved_session_list);
        content.findViewById(R.id.library_create).setOnClickListener(v -> { libraryDialog.dismiss(); showCreate(); });
        content.findViewById(R.id.library_join).setOnClickListener(v -> { libraryDialog.dismiss(); showJoin(); });
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext()).setTitle(R.string.sessions_title)
                .setView(content).setPositiveButton(R.string.connection_close, null);
        if (session != null) builder.setNegativeButton(R.string.connection_leave, (d, which) -> coordinator.leave());
        libraryDialog = builder.create();
        libraryDialog.setOnDismissListener(d -> libraryList = null);
        renderLibrary();
        libraryDialog.show();
        if (session != null) {
            libraryDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setId(R.id.session_leave);
            libraryDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
                    androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cyber_danger));
        }
    }
    private void renderLibrary() {
        LinearLayout list = libraryList;
        if (list == null) return;
        list.removeAllViews();
        for (SavedSessionStore.Room saved : coordinator.store.all()) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(16), dp(16), dp(10));
            row.setBackgroundResource(R.drawable.pos_card);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.bottomMargin = dp(10);
            row.setLayoutParams(rowParams);
            TextView name = new TextView(requireContext());
            name.setId(R.id.saved_session_name); name.setText(saved.name); name.setTextSize(16);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setPadding(0, 0, 0, dp(8));
            name.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cyber_text)); row.addView(name);
            LinearLayout actions = new LinearLayout(requireContext());
            Button enter = new Button(requireContext());
            boolean active = saved.session.code.equals(displayedCode);
            enter.setId(R.id.saved_session_enter); enter.setText(active ? R.string.sessions_active : R.string.sessions_enter);
            enter.setEnabled(!active); enter.setOnClickListener(view -> {
                libraryDialog.dismiss();
                coordinator.activate(saved);
            });
            styleSessionButton(enter);
            actions.addView(enter, new LinearLayout.LayoutParams(0, -2, 1));
            Button options = new Button(requireContext()); options.setId(R.id.saved_session_options);
            options.setText(R.string.sessions_options); options.setOnClickListener(view -> showOptions(saved));
            styleSessionButton(options);
            LinearLayout.LayoutParams optionsParams = new LinearLayout.LayoutParams(0, -2, 1);
            optionsParams.setMarginStart(dp(8));
            actions.addView(options, optionsParams);
            row.addView(actions); list.addView(row);
        }
    }
    private void styleSessionButton(Button button) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackgroundResource(R.drawable.session_role_choice);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cyber_muted),
                        androidx.core.content.ContextCompat.getColor(requireContext(), R.color.cyber_cyan)}));
    }
    private void showCreate() { editName(null); }
    private void editName(@Nullable SavedSessionStore.Room saved) {
        EditText name = new EditText(requireContext()); name.setId(R.id.session_name_input);
        name.setSingleLine(); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        name.setHint(R.string.sessions_name_hint);
        name.setText(saved == null ? getString(R.string.sessions_default) + " " + (coordinator.store.all().size()+1) : saved.name);
        LinearLayout box = new LinearLayout(requireContext()); box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(saved == null ? R.string.sessions_new_name : R.string.sessions_rename)
                .setView(box).setPositiveButton(saved == null ? R.string.connection_create : R.string.button_ok, null)
                .setNegativeButton(R.string.connection_cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty() || value.length() > 60 || value.matches(".*[\\p{Cntrl}].*")) { name.setError(getString(R.string.sessions_invalid_name)); return; }
            dialog.dismiss();
            if (saved == null) coordinator.create(value); else coordinator.rename(saved, value);
        }));
        dialog.show();
    }
    private void showOptions(SavedSessionStore.Room saved) {
        String[] choices = saved.session.owner.isEmpty()
                ? new String[]{getString(R.string.sessions_rename), getString(R.string.sessions_forget)}
                : new String[]{getString(R.string.sessions_rename), getString(R.string.sessions_forget), getString(R.string.sessions_delete)};
        new AlertDialog.Builder(requireContext()).setTitle(saved.name).setItems(choices, (dialog, which) -> {
            if (which == 0) { editName(saved); return; }
            new AlertDialog.Builder(requireContext()).setTitle(choices[which])
                    .setMessage(which == 1 ? R.string.sessions_forget_confirm : R.string.sessions_delete_confirm)
                    .setPositiveButton(R.string.button_ok, (confirm, button) -> {
                        if (which == 1) coordinator.forget(saved); else coordinator.delete(saved);
                    }).setNegativeButton(R.string.connection_cancel, null).show();
        }).show();
    }
    @Override protected void onSelect(boolean reader) {
        if (!coordinator.state().connected()) return;
        selectedReader = reader;
        roleChosen = true;
        renderWizard();
        ((android.widget.ScrollView) lobbyView).smoothScrollTo(0, 0);
    }
    private void startSelectedRole() {
        if (!roleChosen || running || !coordinator.state().connected() || !checkNetwork()) return;
        running = true;
        getNfc().startMode(new UIRelayMode(selectedReader));
        coordinator.setDataListener(data -> getNfc().handleData(true, data));
        renderWizard();
    }
    @Override public void onSIDChanged(long sessionID) {
        // Keep recording to the database; raw protocol logs are viewed in the Logs tab.
    }
    @Override public void onDestroyView() {
        stopRole();
        if (joinDialog != null) joinDialog.dismiss();
        if (inviteDialog != null) inviteDialog.dismiss();
        if (libraryDialog != null) libraryDialog.dismiss();
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
            coordinator.send(data);
        }
        @Override public void onData(boolean isForeign, NfcComm data) {
            mLogInserter.log(data);
            if (getActivity() != null) getActivity().runOnUiThread(() -> {
                if (lobbyView != null && running) lobbyView.<TextView>findViewById(R.id.nfc_activity_status)
                        .setText(R.string.wizard_nfc_received);
            });
            super.onData(isForeign, data);
        }
    }
}
