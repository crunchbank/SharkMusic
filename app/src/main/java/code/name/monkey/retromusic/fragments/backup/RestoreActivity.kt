/*
 * Modifications copyright (c) 2026 crunchbank (Shark Music): shows that a restore is running and
 * when it has finished (or failed), and keeps the screen open until then.
 * Licensed under the GNU General Public License v3.
 */
package code.name.monkey.retromusic.fragments.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.MainActivity
import code.name.monkey.retromusic.databinding.ActivityRestoreBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.accentOutlineColor
import code.name.monkey.retromusic.extensions.addAccentColor
import code.name.monkey.retromusic.helper.BackupContent
import code.name.monkey.retromusic.helper.BackupContent.*
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.theme.getNightMode
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import code.name.monkey.retromusic.fragments.backup.BackupViewModel.RestoreState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess


class RestoreActivity : AppCompatActivity() {

    lateinit var binding: ActivityRestoreBinding
    private val backupViewModel: BackupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        updateTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setWidth()
        val backupUri = intent?.data
        binding.backupName.setText(getFileName(backupUri))
        binding.cancelButton.accentOutlineColor()
        binding.cancelButton.setOnClickListener {
            finish()
        }
        binding.restoreButton.accentColor()
        binding.doneButton.accentColor()
        binding.doneButton.setOnClickListener { finish() }
        binding.checkArtistImages.addAccentColor()
        binding.checkPlaylists.addAccentColor()
        binding.checkSettings.addAccentColor()
        binding.checkUserImages.addAccentColor()
        checkBoxes().forEach { box ->
            box.setOnCheckedChangeListener { _, _ ->
                updateRestoreButton()
                backupViewModel.clearRestoreResult()
            }
        }
        binding.restoreButton.setOnClickListener {
            val backupContents = mutableListOf<BackupContent>()
            if (binding.checkPlaylists.isChecked) backupContents.add(PLAYLISTS)
            if (binding.checkArtistImages.isChecked) backupContents.add(CUSTOM_ARTIST_IMAGES)
            if (binding.checkSettings.isChecked) backupContents.add(SETTINGS)
            if (binding.checkUserImages.isChecked) backupContents.add(USER_IMAGES)
            if (backupUri != null) {
                backupViewModel.restoreBackup(this, backupUri, backupContents)
            }
        }
        // Leaving mid-restore would hide the result (and skip the restart settings need).
        val blockBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {}
        }
        onBackPressedDispatcher.addCallback(this, blockBack)
        backupViewModel.restoreState.observe(this) { state ->
            blockBack.isEnabled = state == RestoreState.Running
            showState(state)
        }
    }

    private fun checkBoxes() = listOf(
        binding.checkSettings, binding.checkPlaylists,
        binding.checkUserImages, binding.checkArtistImages
    )

    private fun updateRestoreButton() {
        binding.restoreButton.isEnabled = checkBoxes().any { it.isChecked }
    }

    private fun showState(state: RestoreState) {
        val running = state == RestoreState.Running
        val done = state is RestoreState.Done
        setFinishOnTouchOutside(!running)
        binding.progress.isVisible = running
        checkBoxes().forEach { it.isEnabled = !running && !done }
        binding.cancelButton.isEnabled = !running
        binding.cancelButton.isVisible = !done
        binding.restoreButton.isVisible = !done
        updateRestoreButton()
        if (running) binding.restoreButton.isEnabled = false
        binding.doneButton.isVisible = done && !state.restartNeeded

        val message = when (state) {
            RestoreState.Idle -> null
            RestoreState.Running -> getString(R.string.restore_in_progress)
            is RestoreState.Done -> getString(
                if (state.restartNeeded) R.string.restore_done_restarting else R.string.message_restore_success
            )
            RestoreState.NothingFound -> getString(R.string.restore_nothing_found)
            RestoreState.Failed -> getString(R.string.restore_failed)
        }
        binding.status.isVisible = message != null
        binding.status.text = message

        if (state is RestoreState.Done && state.restartNeeded) {
            // Restored settings only take effect in a fresh process: give the message a moment
            // to be read, then restart.
            lifecycleScope.launch {
                delay(RESTART_DELAY_MS)
                startActivity(
                    Intent(this@RestoreActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                exitProcess(0)
            }
        }
    }

    private fun updateTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())

        // Apply dynamic colors to activity if enabled
        if (PreferenceUtil.materialYou) {
            DynamicColors.applyToActivityIfAvailable(
                this,
                DynamicColorsOptions.Builder()
                    .setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight)
                    .build()
            )
        }
    }

    private fun getFileName(uri: Uri?): String? {
        when (uri?.scheme) {
            ContentResolver.SCHEME_FILE -> {
                return uri.lastPathSegment
            }
            ContentResolver.SCHEME_CONTENT -> {
                val proj = arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME)
                contentResolver.query(
                    uri, proj, null, null, null
                )?.use { cursor ->
                    if (cursor.count != 0) {
                        val columnIndex: Int =
                            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        return cursor.getString(columnIndex)
                    }
                }
            }
        }
        return "Backup"
    }

    companion object {
        private const val RESTART_DELAY_MS = 1500L
    }

    private fun setWidth() {
        val width = resources.displayMetrics.widthPixels * 0.8
        binding.root.updateLayoutParams<ViewGroup.LayoutParams> { this.width = width.toInt() }
    }
}