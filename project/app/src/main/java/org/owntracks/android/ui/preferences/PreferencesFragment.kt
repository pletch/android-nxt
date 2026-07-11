package org.owntracks.android.ui.preferences

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.owntracks.android.R
import org.owntracks.android.ui.preferences.editor.EditorActivity

@AndroidEntryPoint
class PreferencesFragment : AbstractPreferenceFragment() {
  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    setPreferencesFromResource(R.xml.preferences_root, rootKey)
    // Have to do this manually here, as there's an android bug that prevents the activity from
    // being found when launched from intent declared on the preferences XML.
    findPreference<Preference>(UI_SCREEN_CONFIGURATION)?.intent =
        Intent(context, EditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
  }

  override fun onResume() {
    super.onResume()
    findPreference<Preference>(UI_PREFERENCE_SCREEN_CONNECTION)?.summary = connectionMode
  }

  companion object {
    private const val UI_PREFERENCE_SCREEN_CONNECTION = "connectionScreen"
    private const val UI_SCREEN_CONFIGURATION = "configuration"
  }
}
