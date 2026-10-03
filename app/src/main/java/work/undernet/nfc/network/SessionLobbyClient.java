package work.undernet.nfc.network;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Locale;

import work.undernet.nfc.network.transport.TLSTransport;

/** One bounded TLS request; relay framing and NFC data remain unchanged. */
public final class SessionLobbyClient {
    private static final String PREFIX = "UNDERNET-LOBBY/1 ";
    public static final String QR_PREFIX = "undernet:join:";

    public static final class Session {
        public final int number;
        public final String secret;
        public final String code;
        Session(int number, String secret, String code) {
            this.number = number;
            this.secret = secret;
            this.code = code;
        }
    }

    public static final class LobbyException extends IOException {
        public final String reason;
        LobbyException(String reason) {
            super("Session request failed");
            this.reason = reason;
        }
    }

    public static String normalizeCode(String value) {
        String code = value.trim();
        if (code.startsWith(QR_PREFIX)) code = code.substring(QR_PREFIX.length());
        code = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (!code.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{16}"))
            throw new IllegalArgumentException("Invalid invitation");
        return code;
    }

    public static Session create() throws IOException { return request(null); }
    public static Session join(String code) throws IOException { return request(normalizeCode(code)); }

    private static Session request(String code) throws IOException {
        TLSTransport transport = new TLSTransport(NetworkManager.SERVER_HOST, NetworkManager.SERVER_PORT);
        try {
            JSONObject message = new JSONObject().put("action", code == null ? "create" : "join");
            if (code != null) message.put("code", code);
            byte[] payload = (PREFIX + message).getBytes(StandardCharsets.UTF_8);
            transport.connect();
            transport.socket().setSoTimeout(10000);
            byte[] frame = ByteBuffer.allocate(5 + payload.length).putInt(payload.length)
                    .put((byte) 0).put(payload).array();
            transport.socket().getOutputStream().write(frame);
            transport.socket().getOutputStream().flush();
            DataInputStream input = new DataInputStream(transport.socket().getInputStream());
            int size = input.readInt();
            if (size < PREFIX.length() || size > 1024) throw new IOException("Invalid session response");
            byte[] bytes = new byte[size];
            input.readFully(bytes);
            // Confirm receipt before the server shuts down this short TLS socket.
            transport.socket().getOutputStream().write(0);
            transport.socket().getOutputStream().flush();
            String reply = new String(bytes, StandardCharsets.UTF_8);
            if (!reply.startsWith(PREFIX)) throw new IOException("Invalid session response");
            JSONObject response = new JSONObject(reply.substring(PREFIX.length()));
            if (!"ok".equals(response.optString("status")))
                throw new LobbyException(response.optString("error", "invalid"));
            int number = response.getInt("session");
            String secret = response.getString("secret");
            String invite = response.getString("code");
            if (number < 1 || number > 255 || secret.length() < 16 || secret.length() > 256)
                throw new IOException("Invalid session response");
            normalizeCode(invite);
            return new Session(number, secret, invite);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("Invalid session response");
        } finally {
            transport.close(false);
        }
    }

    private SessionLobbyClient() { }
}
