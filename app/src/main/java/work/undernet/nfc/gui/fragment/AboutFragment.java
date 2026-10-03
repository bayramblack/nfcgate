package work.undernet.nfc.gui.fragment;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import work.undernet.nfc.BuildConfig;
import mehdi.sakout.aboutpage.AboutPage;
import mehdi.sakout.aboutpage.Element;
import work.undernet.nfc.R;

public class AboutFragment extends Fragment {
    public static String getVersionNameGit() {
        if (!BuildConfig.GIT_COMMIT_HASH.isEmpty())
            return String.format("%s (%s)", BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT_HASH);
        else
            return BuildConfig.VERSION_NAME;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View aboutPage = inflater.inflate(R.layout.fragment_about, container, false);
        TextView description = aboutPage.findViewById(R.id.about_description);
        description.setText(Html.fromHtml(getString(R.string.about_text)));
        aboutPage.<TextView>findViewById(R.id.about_version_label)
                .setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));
        aboutPage.findViewById(R.id.about_website_button).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://UnderNet.work"))));
        aboutPage.findViewById(R.id.about_license_button).setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.apache.org/licenses/LICENSE-2.0"))));
        return aboutPage;
    }
}
