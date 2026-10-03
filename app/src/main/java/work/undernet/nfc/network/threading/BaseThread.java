package work.undernet.nfc.network.threading;

import java.io.IOException;
import java.net.Socket;

import javax.net.ssl.SSLException;

import work.undernet.nfc.network.ServerConnection;
import work.undernet.nfc.network.SessionAuthentication;
import work.undernet.nfc.network.UserTrustManager;
import work.undernet.nfc.network.data.NetworkStatus;

/**
 * An interruptible thread that properly handles interrupt()
 */
public abstract class BaseThread extends Thread {
    // set on interrupt
    private boolean mExit = false;

    final ServerConnection mConnection;
    Socket mSocket;

    BaseThread(ServerConnection connection) {
        mConnection = connection;

        // ensure JVM stops this thread at the end of app
        setDaemon(true);
    }

    @Override
    public void run() {
        // per-thread init
        try {
            mSocket = mConnection.openSocket();
            if (mSocket == null)
                throw new IOException("openSocket failed");

            initThread();
        } catch (Exception e) {
            mExit = true;
            onError(e);
        }

        while (!mExit && !Thread.currentThread().isInterrupted()) {
            try {
                runInternal();
            }
            catch (InterruptedException e) {
                // Preserve cancellation so the sender exits and closes the socket.
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                mExit = true;
                onError(e);
            }
        }

        // close socket
        mConnection.closeSocket();
    }

    abstract void initThread() throws IOException;
    abstract void runInternal() throws IOException, InterruptedException;
    void onError(Exception e) {
        // get innermost exception
        Throwable cause = e;
        while (cause.getCause() != null)
            cause = cause.getCause();

        // specific causes
        if (cause instanceof SessionAuthentication.AuthenticationException)
            mConnection.reportStatus(NetworkStatus.ERROR_AUTH);
        else if (cause instanceof UserTrustManager.UnknownTrustException)
            mConnection.reportStatus(NetworkStatus.ERROR_TLS_CERT_UNKNOWN);
        else if (cause instanceof UserTrustManager.UntrustedException)
            mConnection.reportStatus(NetworkStatus.ERROR_TLS_CERT_UNTRUSTED);
        // general failures
        else if (e instanceof SSLException)
            mConnection.reportStatus(NetworkStatus.ERROR_TLS);
        else
            mConnection.reportStatus(NetworkStatus.ERROR);
    }
}
