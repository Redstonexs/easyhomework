package com.easyhomework.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.easyhomework.app.data.AppDatabase
import com.easyhomework.app.model.QueryHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val historyDao = database.historyDao()

    private val _historyList = MutableStateFlow<List<QueryHistory>>(emptyList())
    val historyList: StateFlow<List<QueryHistory>> = _historyList.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            historyDao.getAllHistory().collect { list ->
                _historyList.value = list
                _isLoading.value = false
            }
        }
    }

    fun deleteHistory(history: QueryHistory) {
        viewModelScope.launch {
            historyDao.deleteHistory(history)
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
            // Clean up screenshot files
            allHistory.forEach { history ->
                try {
                    java.io.File(history.screenshotPath).delete()
                } catch (_: Exception) {}
            }
        }
    }
}
