package com.trashtalk.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.trashtalk.data.AdvocacyAction
import com.trashtalk.data.NeighborhoodNetwork
import com.trashtalk.data.TrashTalkRepository
import com.trashtalk.data.model.BackendMode
import com.trashtalk.data.model.CollectionEvent
import com.trashtalk.data.model.FlagKind
import com.trashtalk.data.model.LeaderboardEntry
import com.trashtalk.data.model.LeaderboardScope
import com.trashtalk.data.model.MapFlag
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.Team
import com.trashtalk.data.model.TrashPath
import com.trashtalk.data.model.UserProfile
import com.trashtalk.domain.CollectionDraft
import com.trashtalk.domain.LiaEngine
import com.trashtalk.domain.ProgressDelta
import com.trashtalk.ui.map.MapLayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One view model for the whole app.
 *
 * The screens share almost all of their state -- the segment list, the profile,
 * the collection feed -- and the app is small enough that splitting it per screen
 * would mean duplicating those subscriptions rather than isolating anything
 * meaningful. Per-screen view models would start paying for themselves at the
 * point this file needs more than one person editing it.
 */
class MainViewModel(private val repo: TrashTalkRepository) : ViewModel() {

    val network: NeighborhoodNetwork get() = repo.network
    val backendMode: BackendMode get() = repo.mode

    // ----------------------------------------------------------- data streams

    val segments: StateFlow<List<Segment>> = repo.observeSegments()
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.network.segments)

    val profile: StateFlow<UserProfile> = repo.observeProfile()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserProfile())

    /**
     * Null means "not loaded yet", empty means "loaded, and there is nothing".
     * Collapsing the two is what made cold starts flash "Nothing logged yet".
     */
    val collections: StateFlow<List<CollectionEvent>?> = repo.observeCollections(200)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val flags: StateFlow<List<MapFlag>> = repo.observeFlags()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val teams: StateFlow<List<Team>?> = repo.observeTeams()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _leaderboardScope = MutableStateFlow(LeaderboardScope.CITY_WEEK)
    val leaderboardScope: StateFlow<LeaderboardScope> = _leaderboardScope.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val leaderboard: StateFlow<List<LeaderboardEntry>?> = _leaderboardScope
        .flatMapLatest { repo.observeLeaderboard(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The assessment, recomputed whenever the underlying observations change. */
    /** Null until the collection feed has arrived; see [collections]. */
    val report: StateFlow<LiaEngine.Report?> = combine(segments, collections) { segs, cols ->
        if (cols == null) {
            null
        } else {
            LiaEngine.build(
                neighborhood = repo.network.neighborhood,
                city = "${repo.network.city}, ${repo.network.state}",
                segments = segs,
                pois = repo.network.pois,
                collections = cols,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------- map state

    private val _mapLayer = MutableStateFlow(MapLayer.DENSITY)
    val mapLayer: StateFlow<MapLayer> = _mapLayer.asStateFlow()

    /** Day in the history window, or null for live values. */
    private val _scrubDay = MutableStateFlow<Int?>(null)
    val scrubDay: StateFlow<Int?> = _scrubDay.asStateFlow()

    private val _selectedSegmentId = MutableStateFlow<String?>(null)
    val selectedSegmentId: StateFlow<String?> = _selectedSegmentId.asStateFlow()

    private val _showGenerators = MutableStateFlow(true)
    val showGenerators: StateFlow<Boolean> = _showGenerators.asStateFlow()

    private val _showFlags = MutableStateFlow(true)
    val showFlags: StateFlow<Boolean> = _showFlags.asStateFlow()

    private val _showCollections = MutableStateFlow(true)
    val showCollections: StateFlow<Boolean> = _showCollections.asStateFlow()

    /** Celebrations: badge unlocks, level-ups, point awards. */
    private val _events = MutableSharedFlow<ProgressDelta>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching { repo.ensureSignedIn() }
                .onFailure { _toasts.tryEmit("Sign-in failed: ${it.message}") }
        }
    }

    fun selectSegment(id: String?) {
        _selectedSegmentId.value = id
    }

    fun segment(id: String?): Segment? =
        id?.let { sid -> segments.value.firstOrNull { it.id == sid } }

    fun setLayer(layer: MapLayer) {
        _mapLayer.value = layer
    }

    fun setScrubDay(day: Int?) {
        _scrubDay.value = day
    }

    fun toggleGenerators() {
        _showGenerators.value = !_showGenerators.value
    }

    fun toggleFlags() {
        _showFlags.value = !_showFlags.value
    }

    fun toggleCollections() {
        _showCollections.value = !_showCollections.value
    }

    fun setLeaderboardScope(scope: LeaderboardScope) {
        _leaderboardScope.value = scope
    }

    /** Collections logged by the signed-in user, newest first; null while loading. */
    fun myCollections(): List<CollectionEvent>? {
        val me = profile.value.id
        return collections.value?.filter { it.userId == me }
    }

    // --------------------------------------------------------------- actions

    fun submitCollection(draft: CollectionDraft, onDone: (ProgressDelta) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repo.submitCollection(draft) }
                .onSuccess {
                    _events.tryEmit(it)
                    onDone(it)
                }
                .onFailure { _toasts.tryEmit("Could not save: ${it.message}") }
        }
    }

    fun plantFlag(
        kind: FlagKind,
        lat: Double,
        lng: Double,
        segmentId: String?,
        note: String,
        anonymous: Boolean = false,
    ) {
        viewModelScope.launch {
            runCatching { repo.plantFlag(kind, lat, lng, segmentId, note, anonymous) }
                .onSuccess {
                    _events.tryEmit(it)
                    _toasts.tryEmit(
                        if (kind == FlagKind.HAZARD) "Hazard reported" else "Flag planted",
                    )
                }
                .onFailure { _toasts.tryEmit("Could not plant the flag: ${it.message}") }
        }
    }

    fun setGuardian(segmentId: String, adopt: Boolean, anonymous: Boolean = true) {
        viewModelScope.launch {
            runCatching { repo.setGuardian(segmentId, adopt, anonymous) }
                .onSuccess {
                    _events.tryEmit(it)
                    _toasts.tryEmit(if (adopt) "Segment adopted" else "Segment released")
                }
                .onFailure { _toasts.tryEmit("Could not update adoption: ${it.message}") }
        }
    }

    fun completeLevel(index: Int) {
        viewModelScope.launch {
            runCatching { repo.completeLevel(index) }
                .onSuccess { _events.tryEmit(it) }
                .onFailure { _toasts.tryEmit("Could not save progress: ${it.message}") }
        }
    }

    fun choosePath(path: TrashPath) {
        viewModelScope.launch {
            runCatching { repo.choosePath(path) }
                .onSuccess {
                    _events.tryEmit(it)
                    _toasts.tryEmit("You are on the ${path.label} path")
                }
                .onFailure { _toasts.tryEmit("Could not set your path: ${it.message}") }
        }
    }

    fun joinTeam(teamId: String?) {
        viewModelScope.launch {
            runCatching { repo.joinTeam(teamId) }
                .onSuccess { _toasts.tryEmit(if (teamId == null) "Left your crew" else "Joined") }
                .onFailure { _toasts.tryEmit("Could not join: ${it.message}") }
        }
    }

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            runCatching { repo.setDisplayName(name) }
                .onFailure { _toasts.tryEmit("Could not rename: ${it.message}") }
        }
    }

    fun recordAdvocacy(action: AdvocacyAction, amount: Int = 1) {
        viewModelScope.launch {
            runCatching { repo.recordAdvocacy(action, amount) }
                .onSuccess {
                    _events.tryEmit(it)
                    _toasts.tryEmit(action.label)
                }
                .onFailure { _toasts.tryEmit("Could not record that: ${it.message}") }
        }
    }

    fun resetProgress() {
        viewModelScope.launch {
            repo.resetProgress()
            _toasts.tryEmit("Progress reset")
        }
    }

    class Factory(private val repo: TrashTalkRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo) as T
    }
}
