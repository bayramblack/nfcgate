package work.undernet.nfc.gui.log;

import androidx.lifecycle.ViewModelProviders;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

import work.undernet.nfc.db.AppDatabase;
import work.undernet.nfc.db.NfcCommEntry;
import work.undernet.nfc.db.SessionLog;
import work.undernet.nfc.db.model.SessionLogEntryViewModel;
import work.undernet.nfc.db.model.SessionLogEntryViewModelFactory;
import work.undernet.nfc.db.pcapng.ISO14443Stream;
import work.undernet.nfc.gui.component.ContentShare;
import work.undernet.nfc.util.NfcComm;

public class LogAction {
    private final Fragment mFragment;
    private final List<NfcComm> mLogItems = new ArrayList<>();

    public LogAction(Fragment fragment) {
        mFragment = fragment;
    }

    public void delete(final SessionLog session) {
        new Thread() {
            @Override
            public void run() {
                AppDatabase.getDatabase(mFragment.getActivity()).sessionLogDao().delete(session);
            }
        }.start();
    }

    public void share(final SessionLog session) {
        // clear previous items
        mLogItems.clear();

        // setup db model
        final SessionLogEntryViewModel mLogEntryModel = ViewModelProviders.of(mFragment, new SessionLogEntryViewModelFactory(
                        mFragment.getActivity().getApplication(), session.getId()))
                .get(SessionLogEntryViewModel.class);

        mLogEntryModel.getSession().observe(mFragment, sessionLogJoin -> {
            if (sessionLogJoin != null && mLogItems.isEmpty()) {
                for (NfcCommEntry nfcCommEntry : sessionLogJoin.getNfcCommEntries())
                    mLogItems.add(nfcCommEntry.getNfcComm());

                share(session, mLogItems);
            }
        });
    }

    public void share(SessionLog sessionLog, List<NfcComm> logItems) {
        // share pcap
        new ContentShare(mFragment.getActivity())
                .setPrefix(sessionLog.toString())
                .setExtension(".pcapng")
                .setMimeType("application/*")
                .setFile(new ISO14443Stream().append(logItems))
                .share();
    }
}
