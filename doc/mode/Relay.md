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

UnderNet opens on the Persian connection screen. No hostname, port, session
number, or password needs to be entered in Settings.

1. First phone: tap Create session and choose a name. The server saves a private
   session and the app connects automatically.
2. Send the invitation code, or display its QR.
3. Second phone: tap Join session, then enter/paste the code or select Scan QR.
   The app resolves the invitation over trusted TLS and connects.
4. The screen shows whether it is waiting for the other phone or both are connected.
5. Choose the roles: Reader is the phone reading the original tag; Tag is the
   phone placed against the external reader. The two roles must differ.
6. Leave session clears the active membership and closes the connection. The room
   stays in My sessions for easy re-entry. Opening Settings or another screen,
   backgrounding the app, or a partner leaving does not clear your membership.
7. My sessions lists all saved rooms. Enter switches to one selected room;
   Options offers a personal display-name change and removal from this phone.
   The creator can also delete the room for everyone (a separate confirmation).

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

Production uses `--lobby-store /var/lib/undernet-relay/rooms.sqlite`. Saved rooms
and invitations survive inactivity, disconnects and relay restarts, until their
creator explicitly deletes them. A separate owner capability is returned only
at creation; an invitation or relay secret cannot authorize deletion.
Default session names originate on the server; renaming a saved entry is a
personal alias on that phone. There is no account-based multi-device library sync.

The app stores its room library and active choice privately. A connected-device
foreground service owns one selected relay connection, independently of fragments.
A persistent notification offers Open and Leave (notification permission is
requested once on Android 13+). Temporary transport failures trigger bounded
reconnection attempts; they do not clear the selected room. Full/deleted-session
rejections preserve the local selection with an explicit Retry/Leave choice.
Reopening a force-stopped app restores its selected room. Android force-stop,
power loss, offline networks and device restrictions can stop an actual socket;
retained membership is not a promise of uninterrupted background transport.

An authenticated ping is sent every 25 seconds. Missing acknowledgement for at
least 75 seconds triggers a reconnect. The server consumes ping/pong frames and
never forwards them to peers/plugins. Stored sessions have no old 300-second idle
expiration; static configured sessions and the optional non-persistent lobby
retain their legacy timeout behavior.

One phone can save many rooms, with one active NFC relay at a time. Every private
room still accepts at most two concurrent connections. The original one-byte
session header limits the whole server to 255 slots; three administrator sessions
leave up to 252 persistent lobby rooms. Forgotten entries do not delete a server
room; its creator can delete it to release capacity.

NFC traffic is captured in `Logging` for later use. Physical NFC/HCE/Xposed
operation still requires compatible real phones; the emulator tests connection
setup, authentication, invitations, QR generation, and UI state only.

Tests:
- Host regression: `python -m unittest test_session_auth test_session_lobby test_persistent_sessions -v`
  in the sibling `nfcgate-server` repository.
- Opt-in Android tests: `LiveSessionLobbyTest`, `SessionLobbyUiTest`, and `PersistentSessionsUiTest` connect to
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
