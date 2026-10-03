package work.undernet.nfc.nfc.modes;

import work.undernet.nfc.network.data.NetworkStatus;
import work.undernet.nfc.util.NfcComm;

public class CloneMode extends BaseMode {
    @Override
    public void onEnable() {
        // reset polling and config
        mManager.resetConfig();
        // enable reader mode
        mManager.setReaderMode(true);
    }

    @Override
    public void onDisable() {
        // reset polling and config after mode ends
        mManager.resetConfig();
        // disable reader mode
        mManager.setReaderMode(false);
    }

    @Override
    public void onData(boolean isForeign, NfcComm data) {
        // disable reader mode
        mManager.setReaderMode(false);
        // clone tag, also disables polling
        mManager.applyData(data);
    }

    @Override
    public void onNetworkStatus(NetworkStatus status) {
        // no-op: clone mode has no network activity
    }
}
