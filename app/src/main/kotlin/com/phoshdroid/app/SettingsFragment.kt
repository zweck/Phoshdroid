package com.phoshdroid.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.phoshdroid.app.proot.ProotService
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "phoshdroid_prefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("reset_factory")?.setOnPreferenceClickListener {
            showResetConfirmation()
            true
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset to Factory")
            .setMessage("This will stop the desktop and re-extract the rootfs. Your changes inside postmarketOS will be lost. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                ProotService.stop(requireContext())
                val filesDir = requireContext().filesDir
                File(filesDir, "rootfs/.extraction_complete").delete()
                File(filesDir, "proot-distro/installed-rootfs/postmarketos").deleteRecursively()
                requireActivity().recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
