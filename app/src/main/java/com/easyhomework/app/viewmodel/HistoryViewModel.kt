package com.easyhomework.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easyhomework.app.data.AppDatabase
import com.easyhomework.app.model.QueryHistory
import com.easyhomework.app.model.QueryHistorySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val historyDao = database.historyDao()

    private val _historyList = MutableStateFlow<List<QueryHistorySummary>>(emptyList())
    val historyList: StateFlow<List<QueryHistorySummary>> = _historyList.asStateFlow()

    private val _expandedHistory = MutableStateFlow<Map<Long, QueryHistory>>(emptyMap())
    val expandedHistory: StateFlow<Map<Long, QueryHistory>> = _expandedHistory.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            historyDao.getHistorySummaries().collect { list ->
                _historyList.value = list
                val ids = list.map { it.id }.toSet()
                _expandedHistory.update { expanded -> expanded.filterKeys { it in ids } }
                _isLoading.value = false
            }
        }
    }

    fun loadHistoryDetail(id: Long) {
        if (_expandedHistory.value.containsKey(id)) return
        viewModelScope.launch {
            historyDao.getHistoryById(id)?.let { history ->
                _expandedHistory.update { expanded -> expanded + (id to history) }
            }
        }
    }

    fun deleteHistory(history: QueryHistorySummary) {
        viewModelScope.launch {
            historyDao.getHistoryById(history.id)?.let { historyDao.deleteHistory(it) }
            _expandedHistory.update { expanded -> expanded - history.id }
            // Also delete the screenshot file
            try {
                java.io.File(history.screenshotPath).delete()
            } catch (_: Exception) {}
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            val allHistory = _historyList.value
            historyDao.clearAllHistory()
            _expandedHistory.value = emptyMap()
            // Clean up screenshot files
            allHistory.forEach { history ->
                try {
                    java.io.File(history.screenshotPath).delete()
                } catch (_: Exception) {}
            }
        }
    }
}
