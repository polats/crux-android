package casa.crux.app.ui.screens.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import casa.crux.app.data.update.UpdateRepository
import casa.crux.app.data.update.UpdateState
import casa.crux.app.data.update.AvailableUpdate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
) : ViewModel() {
    val updateState: StateFlow<UpdateState> = updateRepository.state

    init {
        viewModelScope.launch { updateRepository.restore() }
    }

    fun checkForUpdates() {
        viewModelScope.launch { updateRepository.check(manual = true) }
    }

    fun prepareInstall(release: AvailableUpdate) {
        viewModelScope.launch { updateRepository.prepareInstall(release) }
    }

    fun installerLaunched() {
        updateRepository.markInstallerLaunched()
    }
}
