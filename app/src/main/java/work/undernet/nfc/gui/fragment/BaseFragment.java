package work.undernet.nfc.gui.fragment;

import androidx.fragment.app.Fragment;

import work.undernet.nfc.gui.MainActivity;
import work.undernet.nfc.nfc.NfcManager;

public abstract class BaseFragment extends Fragment {
    protected NfcManager getNfc() {
        return getMainActivity().getNfc();
    }

    protected MainActivity getMainActivity() {
        return ((MainActivity) getActivity());
    }
}
