package work.undernet.nfc.nfc.modes;

import work.undernet.nfc.network.data.NetworkStatus;
import work.undernet.nfc.nfc.NfcManager;
import work.undernet.nfc.util.NfcComm;

public abstract class BaseMode {
    protected NfcManager mManager;

    // used by manager to set own reference before enabling this mode
    public void setManager(NfcManager manager) {
        mManager = manager;
    }

    // lifetime methods
    public abstract void onEnable();
    public abstract void onDisable();

    // action and log methods
    public abstract void onData(boolean isForeign, NfcComm data);
    public abstract void onNetworkStatus(NetworkStatus status);
}
