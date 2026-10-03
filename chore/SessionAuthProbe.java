import work.undernet.nfc.network.SessionAuthentication;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

/** Runs the actual Android authentication helper against the Python TLS server. */
public class SessionAuthProbe {
    static final String SECRET = "A".repeat(32);
    static SSLContext context;
    static int port;

    static SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.startHandshake();
        return socket;
    }

    static void send(Socket socket, int session, String message) throws Exception {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length);
        output.writeByte(session);
        output.write(bytes);
        output.flush();
    }

    static String receive(Socket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] bytes = new byte[input.readInt()];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void rejected(int session, String secret) throws Exception {
        try (SSLSocket socket = connect()) {
            try {
                SessionAuthentication.authenticate(socket, session, secret);
                throw new AssertionError("Authentication should have failed");
            } catch (SessionAuthentication.AuthenticationException expected) {
                if (expected.getMessage().contains(secret))
                    throw new AssertionError("Credential leaked in error");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        port = Integer.parseInt(args[0]);
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        try (FileInputStream cert = new FileInputStream(args[2])) {
            store.setCertificateEntry("test", CertificateFactory.getInstance("X.509").generateCertificate(cert));
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);

        rejected(1, "wrong".repeat(8));
        rejected(9, SECRET);
        try (SSLSocket a = connect(); SSLSocket b = connect(); SSLSocket c = connect()) {
            SessionAuthentication.authenticate(a, 1, SECRET);
            SessionAuthentication.authenticate(b, 1, SECRET);
            SessionAuthentication.authenticate(c, 2, "B".repeat(32));
            if (a.getSoTimeout() != 2000)
                throw new AssertionError("Authentication changed relay timeout");
            rejected(1, SECRET); // Two slots already occupied.
            send(a, 1, "forward");
            if (!receive(b).equals("forward")) throw new AssertionError("Relay failed");
            send(b, 1, "reply");
            if (!receive(a).equals("reply")) throw new AssertionError("Reverse relay failed");
            c.setSoTimeout(100);
            try {
                c.getInputStream().read();
                throw new AssertionError("Different session received data");
            } catch (java.net.SocketTimeoutException expected) { }
        }
        // No credential may be sent on a plaintext connection.
        try (Socket plain = new Socket("127.0.0.1", Integer.parseInt(args[1]))) {
            try {
                SessionAuthentication.authenticate(plain, 1, SECRET);
                throw new AssertionError("Plaintext authentication accepted");
            } catch (SessionAuthentication.AuthenticationException expected) { }
        }
        // Unmodified framing still works when authentication is not requested.
        try (Socket a = new Socket("127.0.0.1", Integer.parseInt(args[1]));
             Socket b = new Socket("127.0.0.1", Integer.parseInt(args[1]))) {
            a.setSoTimeout(2000);
            send(a, 1, "register");
            Thread.sleep(100);
            send(b, 1, "legacy");
            if (!receive(a).equals("legacy")) throw new AssertionError("Legacy connection broken");
        }
        System.out.println("PASS: TLS auth, wrong/unknown/full rejection, isolated two-way relay, plaintext refusal, legacy framing");
    }
}
