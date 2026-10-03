"""Host integration check. Requires a JDK, Python cryptography and sibling server repo."""
from contextlib import ExitStack
from datetime import datetime, timedelta, timezone
import ipaddress
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root.parent / "nfcgate-server"))
from server import NFCGateServer, NFCGateClientHandler


def start_server(stack, **kwargs):
    srv = NFCGateServer(("127.0.0.1", 0), NFCGateClientHandler, [], **kwargs)
    # Test credentials and relay payloads must not be printed.
    srv.log = lambda *args, **kwargs: None
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    def stop():
        srv.shutdown()
        srv.server_close()
        thread.join()
    stack.callback(stop)
    return srv.server_address[1]


with tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
    scratch = Path(directory)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "127.0.0.1")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=1)).not_valid_after(now + timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
            .sign(key, hashes.SHA256()))
    cert_path, key_path = scratch / "cert.pem", scratch / "key.pem"
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_path.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                          serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert_path, key_path)
    private_port = start_server(stack, tls_options={"context": ctx, "cert_file": str(cert_path),
                                                    "key_file": str(key_path)},
                                session_secrets={1: b"A" * 32, 2: b"B" * 32})
    legacy_port = start_server(stack)
    source = root / "app/src/main/java/work/undernet/nfc/network/SessionAuthentication.java"
    subprocess.run(["javac", "-d", str(scratch), str(source), str(root / "chore/SessionAuthProbe.java")], check=True)
    subprocess.run(["java", "-cp", str(scratch), "SessionAuthProbe", str(private_port),
                    str(legacy_port), str(cert_path)], check=True, timeout=30)
