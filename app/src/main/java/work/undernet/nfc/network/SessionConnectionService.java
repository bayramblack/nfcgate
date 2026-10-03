package work.undernet.nfc.network;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;
import work.undernet.nfc.R;
import work.undernet.nfc.gui.MainActivity;

/** Keeps the selected peer connection alive when navigating or backgrounding the UI. */
public class SessionConnectionService extends Service {
    private static final String CHANNEL = "session_connection";
    private static final int NOTIFICATION = 31;
    private static final String LEAVE = "work.undernet.nfc.LEAVE_SESSION";
    private SessionCoordinator coordinator;
    private final Observer<SessionCoordinator.State> observer = state -> {
        if (state.room == null && !coordinator.isBusy()) { stopForeground(true); stopSelf(); }
        else ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification());
    };
    @Override public void onCreate() {
        super.onCreate();
        UserTrustManager.init(this);
        coordinator = SessionCoordinator.get(this);
        if (Build.VERSION.SDK_INT >= 26) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.sessions_notification_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification());
        coordinator.states().observeForever(observer);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && LEAVE.equals(intent.getAction())) { coordinator.leave(); return START_NOT_STICKY; }
        if (coordinator.store.active() == null && !coordinator.isBusy()) { stopSelf(); return START_NOT_STICKY; }
        coordinator.connectActive();
        return START_STICKY;
    }
    private Notification notification() {
        SessionCoordinator.State state = coordinator.state();
        Intent open = new Intent(this, MainActivity.class).setAction("work.undernet.nfc.OPEN_SESSION");
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent leave = PendingIntent.getService(this, 1, new Intent(this, SessionConnectionService.class).setAction(LEAVE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int message = state.phase == SessionCoordinator.Phase.READY ? R.string.connection_ready
                : state.connected() ? R.string.connection_waiting : R.string.sessions_reconnecting;
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_relay_black_24dp)
                .setContentTitle(state.room == null ? getString(R.string.app_name) : state.room.name)
                .setContentText(getString(message)).setContentIntent(content).setOngoing(true)
                .addAction(0, getString(R.string.connection_leave), leave).build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        coordinator.states().removeObserver(observer);
        coordinator.suspend();
        super.onDestroy();
    }
}
