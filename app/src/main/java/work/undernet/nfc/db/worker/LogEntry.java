package work.undernet.nfc.db.worker;

import work.undernet.nfc.util.NfcComm;

public class LogEntry {
    private final boolean mValid;
    private final NfcComm mData;

    LogEntry() {
        mData = null;
        mValid = false;
    }

    LogEntry(NfcComm data) {
        mData = data;
        mValid = true;
    }

    NfcComm getData() {
        return mData;
    }

    boolean isValid() {
        return mValid;
    }
}
