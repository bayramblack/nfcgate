Relay Mode
=======

Relay mode enables the user to relay NFC traffic over the network. All traffic on the ISO 14443 layer
can be relayed, along with initial tag information. Please see [clone mode](/doc/mode/Clone.md) for more information.

## Requirements

### Devices
- Two Android devices that will perform the relaying (called *relay devices*). A device cannot be used as both the reader and the tag (HCE) when participating in relaying.
- Reader and tag whose traffic should be relayed (called *relayed devices*). These devices can be Android devices.
- Any device running the [UnderNet server](https://github.com/nfcgate/server/) application. This can also be one of the Android devices running Termux.

### Configuration
Relay devices:

- Android 4.4+ (API level 19+)
- [EdXposed](https://github.com/ElderDrivers/EdXposed) or [Xposed](https://repo.xposed.info/)
- Architecture: ARMv8-A, ARMv7
- The device acting as tag (in "tag mode") requires [HCE support](https://developer.android.com/guide/topics/connectivity/nfc/hce).
- Networking connectivity with the server application, more specifically a TCP connection is required.

Relayed devices:

- No special configuration required.

## Usage

UnderNet opens on the Persian connection screen (`????? ? ????`). No hostname,
port, session number, or password needs to be entered in Settings.

1. First phone: tap **???? ????** (Create session). The server creates a temporary
   private session and the app connects automatically.
2. Send the invitation with **????? ??**, or display **????? QR**.
3. Second phone: tap **?????? ?? ????** (Join session), then enter/paste the code
   or tap **???? QR**. The app resolves the invitation over trusted TLS and connects.
4. The screen shows whether it is waiting for the other phone or both are connected.
5. Choose the roles: **Reader** is the phone reading the original tag; **Tag** is
   the phone placed against the external reader. The two roles must differ.
6. **???? ?? ????** leaves the session. Opening another section also closes its
   relay connection. Return to the connection screen to create or join again.

The fixed endpoint is `relay.undernet.work:5566`. The invitation is a random
16-character code (hyphens are optional). The QR contains `undernet:join:<code>`;
it does not select arbitrary servers. Camera scanning requires camera permission;
manual entry remains available. NFC is checked only when selecting a role, so the
connection workflow can be viewed and tested on the emulator.

### Private session behavior

The server requires TLS and authenticates both devices before normal relay data.
The two-device limit and fixed authenticated session remain enforced. Anyone
holding the invitation can request credentials and occupy an available slot;
there is no account identity, owner approval, or server-enforced Reader/Tag role.

An unused new invitation expires after 10 minutes. While at least one device is
connected it remains valid. After the last device leaves it expires after 60
seconds. Temporary invitations are stored in server memory and are lost on server
restart. The existing three administrator-configured sessions remain supported
by the protocol. Their credentials are no longer editable in the normal Settings UI.
Online Replay uses the last automatically received session credentials.

A public TLS socket with no traffic still has the existing 300-second idle timeout.
For an inactive session that disconnects, create or join again from this screen.

NFC traffic is captured in `Logging` for later use. Physical NFC/HCE/Xposed
operation still requires compatible real phones; the emulator tests connection
setup, authentication, invitations, QR generation, and UI state only.

Tests:
- Host regression: `python -m unittest test_session_auth test_session_lobby -v`
  in the sibling `nfcgate-server` repository.
- Opt-in Android tests: `LiveSessionLobbyTest` and `SessionLobbyUiTest` connect to
  the deployed service and create temporary test sessions.
- The original Java/Python authentication probe remains in
  `chore/test_session_auth.py` (JDK and Python cryptography required).

## Technical Information
See [clone mode documentation](/doc/mode/Clone.md).

### Architecture
![Relay mode architecture](/doc/media/architecture_connect.png)

## Caveats
### Timing
If the relayed reader and/or tag have constrained requirements for communication time, timeouts can occur.
In addition to that, poor network connectivity might also negatively affect performance and reliability.
