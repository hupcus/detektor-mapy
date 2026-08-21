package cz.hh.detektormapy.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.net.UpdateChecker
import cz.hh.detektormapy.net.UpdateStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the manual "is there a newer build?" check. */
sealed interface UpdateCheck {
    data object Idle : UpdateCheck

    data object Checking : UpdateCheck

    data class Done(val status: UpdateStatus) : UpdateCheck
}

/**
 * Backs the version screen. Deliberately its own holder rather than a slice of
 * `SettingsViewModel`: this screen needs one collaborator, and borrowing the settings holder
 * would drag exporters, importers and four repositories onto a page that shows a version number.
 */
@HiltViewModel
class VersionViewModel @Inject constructor(private val updateChecker: UpdateChecker) : ViewModel() {

    private val checkState = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val check: StateFlow<UpdateCheck> = checkState

    private val messageState = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = messageState

    /**
     * Asks GitHub whether a newer release exists. Only ever on a tap: nothing polls, so the
     * app's promise that it talks to nobody unless asked stays true.
     */
    fun checkForUpdate() {
        if (checkState.value == UpdateCheck.Checking) return
        checkState.value = UpdateCheck.Checking
        viewModelScope.launch {
            val status = runCatching { updateChecker.check() }.getOrDefault(UpdateStatus.Unavailable)
            checkState.value = UpdateCheck.Done(status)
        }
    }

    fun notify(text: String) {
        messageState.value = text
    }

    fun consumeMessage() {
        messageState.value = null
    }
}
