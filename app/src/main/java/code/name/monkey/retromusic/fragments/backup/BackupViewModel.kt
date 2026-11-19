/*
 * Modifications copyright (c) 2026 crunchbank (Shark Music): the restore runs in the view model
 * and reports its progress and result through restoreState, so the restore screen can show them.
 * Licensed under the GNU General Public License v3.
 */
package code.name.monkey.retromusic.fragments.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.helper.BackupContent
import code.name.monkey.retromusic.helper.BackupHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class BackupViewModel : ViewModel() {
    private val backupsMutableLiveData = MutableLiveData<List<File>>()
    val backupsLiveData: LiveData<List<File>> = backupsMutableLiveData

    sealed class RestoreState {
        object Idle : RestoreState()
        object Running : RestoreState()

        /** [restartNeeded]: settings or artist images were restored, which only take effect after a restart. */
        class Done(val restartNeeded: Boolean) : RestoreState()

        /** The file had none of the chosen items (or isn't a backup). */
        object NothingFound : RestoreState()
        object Failed : RestoreState()
    }

    private val restoreStateMutable = MutableLiveData<RestoreState>(RestoreState.Idle)
    val restoreState: LiveData<RestoreState> = restoreStateMutable

    fun loadBackups() {
        BackupHelper.getBackupRoot().listFiles { _, name ->
            return@listFiles name.endsWith(BackupHelper.BACKUP_EXTENSION)
        }?.toList()?.let {
            backupsMutableLiveData.value = it
        }
    }

    /** Clears a "nothing found" or "failed" result, e.g. when the user changes what to restore. */
    fun clearRestoreResult() {
        val state = restoreStateMutable.value
        if (state == RestoreState.NothingFound || state == RestoreState.Failed) {
            restoreStateMutable.value = RestoreState.Idle
        }
    }

    fun restoreBackup(context: Context, backupUri: Uri, contents: List<BackupContent>) {
        if (restoreStateMutable.value == RestoreState.Running) return
        restoreStateMutable.value = RestoreState.Running
        val appContext = context.applicationContext
        viewModelScope.launch {
            val restored = try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(backupUri)?.use {
                        BackupHelper.restoreBackup(appContext, it, contents)
                    } ?: throw IllegalStateException("Can't open $backupUri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                restoreStateMutable.value = RestoreState.Failed
                return@launch
            }
            restoreStateMutable.value = if (restored == 0) {
                RestoreState.NothingFound
            } else {
                RestoreState.Done(
                    restartNeeded = BackupContent.SETTINGS in contents ||
                            BackupContent.CUSTOM_ARTIST_IMAGES in contents
                )
            }
        }
    }

    companion object {
        private const val TAG = "BackupViewModel"
    }
}
