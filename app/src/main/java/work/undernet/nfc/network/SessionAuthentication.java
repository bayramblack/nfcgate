package work.undernet.nfc.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.net.ssl.SSLSocket;

/** Optional startup exchange; the existing relay messages are unchanged. */
public final class SessionAuthentication {
    private static final byte[] PREFIX = "UNDERNET-AUTH/1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OK = "UNDERNET-AUTH/1 OK".getBytes(StandardCharsets.UTF_8);

    private SessionAuthentication() { }

    public static final class AuthenticationException extends IOException {
        AuthenticationException() {
            super("Session authentication failed; check session, secret, and available slots");
        }
    }

    public static void authenticate(Socket socket, int session, String secret) throws IOException {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (!(socket instanceof SSLSocket) || session < 1 || session > 255 ||
                secretBytes.length < 16 || secretBytes.length > 256)
            throw new AuthenticationException();

        int previousTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(10000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeInt(PREFIX.length + secretBytes.length);
            output.writeByte(session);
            output.write(PREFIX);
            output.write(secretBytes);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            if (input.readInt() != OK.length)
                throw new AuthenticationException();
            byte[] reply = new byte[OK.length];
            input.readFully(reply);
            if (!Arrays.equals(reply, OK))
                throw new AuthenticationException();
        } catch (IOException failure) {
            // Do not include credentials or untrusted server replies in errors/logs.
            throw new AuthenticationException();
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
            socket.setSoTimeout(previousTimeout);
        }
    }
}
