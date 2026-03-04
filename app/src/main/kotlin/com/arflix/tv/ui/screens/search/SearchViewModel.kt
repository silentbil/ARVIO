package com.arflix.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchSuggestion(
    val title: String,
    val mediaType: MediaType,
    val mediaId: Int,
    val year: String = ""
)

data class SearchUiState(
    val query: String = "",
    val suggestions: List<SearchSuggestion> = emptyList(),
    val isLoading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val movieResults: List<MediaItem> = emptyList(),
    val tvResults: List<MediaItem> = emptyList(),
    val cardLogoUrls: Map<String, String> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var suggestionJob: Job? = null
    private var cachedSuggestionQuery: String = ""
    private var cachedSuggestionResults: List<MediaItem> = emptyList()

    fun addChar(char: String) {
        updateQuery(_uiState.value.query + char)
    }

    fun deleteChar() {
        if (_uiState.value.query.isNotEmpty()) {
            updateQuery(_uiState.value.query.dropLast(1))
        }
    }

    fun updateQuery(newQuery: String) {
        _uiState.value = _uiState.value.copy(query = newQuery)
        if (newQuery.trim().isEmpty()) {
            cachedSuggestionQuery = ""
            cachedSuggestionResults = emptyList()
            _uiState.value = SearchUiState()
            suggestionJob?.cancel()
            searchJob?.cancel()
            return
        }
        debounceSuggestions()
        debounceSearch()
    }

    fun applySuggestion(suggestion: SearchSuggestion) {
        _uiState.value = _uiState.value.copy(query = suggestion.title)
        search()
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val sortedResults = if (cachedSuggestionQuery.equals(query, ignoreCase = true) && cachedSuggestionResults.isNotEmpty()) {
                    cachedSuggestionResults
                } else {
                    val fetched = mediaRepository.search(query)
                    val sorted = sortResults(query, fetched)
                    cachedSuggestionQuery = query
                    cachedSuggestionResults = sorted
                    sorted
                }

                // Separate into movies and TV shows
                val movies = sortedResults.filter { it.mediaType == MediaType.MOVIE }
                val tvShows = sortedResults.filter { it.mediaType == MediaType.TV }
                val suggestions = buildSuggestions(query, sortedResults)
                val topForLogos = (movies.take(16) + tvShows.take(16)).distinctBy { "${it.mediaType}_${it.id}" }
                val logoMap = withContext(Dispatchers.IO) {
                    topForLogos.map { item ->
                        async {
                            val key = "${item.mediaType}_${item.id}"
                            val logo = runCatching { mediaRepository.getLogoUrl(item.mediaType, item.id) }
                                .getOrNull()
                            if (logo.isNullOrBlank()) null else key to logo
                        }
                    }.awaitAll().filterNotNull().toMap()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    results = sortedResults,
                    movieResults = movies,
                    tvResults = tvShows,
                    suggestions = suggestions,
                    cardLogoUrls = logoMap
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun debounceSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(450) // Snappier TV keyboard feedback while preserving network debounce
            if (_uiState.value.query.length >= 2) {
                search()
            }
        }
    }

    private fun debounceSuggestions() {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(180)
            val query = _uiState.value.query.trim()
            if (query.length < 2) {
                _uiState.value = _uiState.value.copy(suggestions = emptyList())
                return@launch
            }

            val sortedResults = if (cachedSuggestionQuery.equals(query, ignoreCase = true) && cachedSuggestionResults.isNotEmpty()) {
                cachedSuggestionResults
            } else {
                val fetched = runCatching { mediaRepository.search(query) }.getOrDefault(emptyList())
                val sorted = sortResults(query, fetched)
                cachedSuggestionQuery = query
                cachedSuggestionResults = sorted
                sorted
            }

            _uiState.value = _uiState.value.copy(suggestions = buildSuggestions(query, sortedResults))
        }
    }

    private fun sortResults(query: String, results: List<MediaItem>): List<MediaItem> {
        val queryLower = query.lowercase()
        return results.sortedWith(
            compareBy<MediaItem> { item ->
                val titleLower = item.title.lowercase()
                when {
                    titleLower == queryLower -> 0
                    titleLower.startsWith(queryLower) -> 1
                    titleLower.contains(queryLower) -> 2
                    else -> 3
                }
            }.thenByDescending { item ->
                val isDocumentary = item.genreIds.contains(99) || item.genreIds.contains(10763)
                val titleLower = item.title.lowercase()
                val isSpecial = titleLower.contains("making of") ||
                    titleLower.contains("behind the") ||
                    titleLower.contains("special") ||
                    titleLower.contains("documentary") ||
                    titleLower.contains("featurette")

                if (isDocumentary || isSpecial) {
                    item.popularity * 0.1f
                } else {
                    item.popularity
                }
            }.thenByDescending { item ->
                item.year.toIntOrNull() ?: 0
            }
        )
    }

    private fun buildSuggestions(query: String, sortedResults: List<MediaItem>): List<SearchSuggestion> {
        val queryLower = query.lowercase()
        return sortedResults
            .filter { item -> item.title.lowercase().contains(queryLower) }
            .distinctBy { "${it.mediaType}_${it.id}" }
            .take(8)
            .map { item ->
                SearchSuggestion(
                    title = item.title,
                    mediaType = item.mediaType,
                    mediaId = item.id,
                    year = item.year
                )
            }
    }

    fun clearSearch() {
        searchJob?.cancel()
        suggestionJob?.cancel()
        cachedSuggestionQuery = ""
        cachedSuggestionResults = emptyList()
        _uiState.value = SearchUiState()
    }
}
