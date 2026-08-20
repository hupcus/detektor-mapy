package cz.hh.detektormapy.ui.finds

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.hh.detektormapy.data.AppDirectories
import cz.hh.detektormapy.data.entity.FindEntity
import cz.hh.detektormapy.data.model.FindCategory
import cz.hh.detektormapy.data.relation.FindWithPhotos
import cz.hh.detektormapy.data.repository.FindFilter
import cz.hh.detektormapy.data.repository.FindsRepository
import cz.hh.detektormapy.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/** Quick date filters offered above the gallery (PLAN.md F2-3). */
enum class DateRange(val label: String) {
    DNES("Dnes"),
    DNU_7("7 dní"),
    DNU_30("30 dní"),
    VSE("Vše"),
}

/** Everything the gallery renders, in one immutable snapshot. */
data class FindsUiState(
    val finds: List<FindWithPhotos> = emptyList(),
    val filter: FindFilter = FindFilter.ALL,
    val dateRange: DateRange = DateRange.VSE,
    /** Counts over *all* finds, not the filtered ones -- the chips have to stay informative. */
    val countsByCategory: Map<FindCategory, Int> = emptyMap(),
    val totalCount: Int = 0,
    val loading: Boolean = true,
    val message: String? = null,
) {
    val filtersActive: Boolean
        get() = filter.categories.isNotEmpty() || filter.favoriteOnly || dateRange != DateRange.VSE
}

/**
 * State holder for the finds gallery.
 *
 * The list comes from [FindsRepository.observeAllWithPhotos] rather than from
 * [FindsRepository.observeFiltered], because the grid needs the primary photo of every card and
 * the filtered query only returns bare [FindEntity] rows. The very same [FindFilter] is applied
 * in memory instead -- a personal finds log is at most a few thousand rows, so one extra pass
 * over the list is far cheaper than a second query per photo.
 */
@HiltViewModel
class FindsViewModel @Inject constructor(
    private val repository: FindsRepository,
    private val directories: AppDirectories,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val categories = MutableStateFlow<Set<FindCategory>>(emptySet())
    private val favoriteOnly = MutableStateFlow(false)
    private val dateRange = MutableStateFlow(DateRange.VSE)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<FindsUiState> = combine(
        repository.observeAllWithPhotos(),
        categories,
        favoriteOnly,
        dateRange,
        message,
    ) { all, cats, favOnly, range, msg ->
        val filter = filterOf(cats, favOnly, range)
        FindsUiState(
            finds = all.filter { matches(it.find, filter) },
            filter = filter,
            dateRange = range,
            countsByCategory = all.groupingBy { it.find.category }.eachCount(),
            totalCount = all.size,
            loading = false,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FindsUiState())

    // --- filtering -------------------------------------------------------------------

    /** Adds or removes one category; an empty set means "every category". */
    fun toggleCategoryFilter(category: FindCategory) {
        categories.update { current ->
            if (category in current) current - category else current + category
        }
    }

    fun setCategoryFilter(selected: Set<FindCategory>) {
        categories.value = selected
    }

    fun toggleFavoriteOnly() {
        favoriteOnly.update { !it }
    }

    fun setDateRange(range: DateRange) {
        dateRange.value = range
    }

    fun clearFilters() {
        categories.value = emptySet()
        favoriteOnly.value = false
        dateRange.value = DateRange.VSE
    }

    // --- mutations -------------------------------------------------------------------

    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            val current = state.value.finds.firstOrNull { it.find.id == id }?.find
                ?: repository.getFind(id)
                ?: return@launch
            runCatching { repository.setFavorite(id, !current.favorite) }
                .onFailure { message.value = "Oblíbenost se nepodařilo změnit" }
        }
    }

    /**
     * Deletes the row and, when the photos live in our own photo directory, the JPEGs too.
     * Files outside that directory are left alone: they may be pictures the user picked from
     * the gallery, and deleting those would be destroying data we never owned.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val withPhotos = repository.getFindWithPhotos(id)
            runCatching { repository.delete(id) }
                .onSuccess {
                    withContext(io) {
                        withPhotos?.photos?.forEach { deleteOwnedPhotoFile(it.uri) }
                    }
                    message.value = "Nález smazán"
                }
                .onFailure { message.value = "Nález se nepodařilo smazat" }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    // --- internals -------------------------------------------------------------------

    private fun deleteOwnedPhotoFile(uri: String) {
        if (!uri.startsWith("/")) return
        runCatching {
            val file = File(uri)
            val root = directories.findsPhotoDir.canonicalPath
            if (file.canonicalPath.startsWith(root) && file.exists()) {
                file.delete()
            }
        }.onFailure { Log.w(TAG, "Fotku $uri se nepodařilo smazat", it) }
    }

    private companion object {
        const val TAG = "FindsViewModel"

        fun filterOf(
            categories: Set<FindCategory>,
            favoriteOnly: Boolean,
            range: DateRange,
            now: Long = System.currentTimeMillis(),
        ): FindFilter {
            val from = when (range) {
                DateRange.DNES -> startOfDay(now)
                DateRange.DNU_7 -> now - 7L * DAY_MS
                DateRange.DNU_30 -> now - 30L * DAY_MS
                DateRange.VSE -> Long.MIN_VALUE
            }
            return FindFilter(
                categories = categories,
                fromMillis = from,
                toMillis = Long.MAX_VALUE,
                favoriteOnly = favoriteOnly,
            )
        }

        fun matches(find: FindEntity, filter: FindFilter): Boolean {
            if (filter.categories.isNotEmpty() && find.category !in filter.categories) return false
            if (filter.favoriteOnly && !find.favorite) return false
            if (find.createdAt < filter.fromMillis) return false
            if (find.createdAt > filter.toMillis) return false
            return true
        }

        const val DAY_MS = 24L * 60L * 60L * 1000L

        fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
