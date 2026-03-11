package com.control.app.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Represents an independent agent session — one task with its own conversation
 * history and debug log.
 */
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
    val messageHistory: MutableList<JsonObject> = mutableListOf(),
    val debugEntries: MutableList<DebugLogEntry> = mutableListOf(),
    var isActive: Boolean = true,
    var result: String? = null
)

/**
 * Manages agent sessions. Each voice command creates a new session with its own
 * conversation history, allowing the agent to maintain independent context per task.
 *
 * Sessions are kept in-memory only (no persistence across app restarts).
 */
class SessionManager {

    companion object {
        private const val TAG = "SessionManager"
        private const val MAX_SESSIONS = 20
    }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSession = MutableStateFlow<Session?>(null)
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    /**
     * Create a new session for the given task and set it as the current session.
     * Automatically prunes old sessions if the limit is exceeded.
     */
    fun createSession(title: String): Session {
        val session = Session(title = title)
        val updated = (_sessions.value + session).sortedByDescending { it.lastActiveAt }

        // Prune oldest sessions beyond the limit
        val pruned = if (updated.size > MAX_SESSIONS) {
            val removed = updated.drop(MAX_SESSIONS)
            for (s in removed) {
                Log.d(TAG, "Pruning old session: ${s.id} (${s.title})")
            }
            updated.take(MAX_SESSIONS)
        } else {
            updated
        }

        _sessions.value = pruned
        _currentSession.value = session
        Log.d(TAG, "Created session ${session.id}: $title")
        return session
    }

    /**
     * Switch to an existing session by ID.
     */
    fun switchToSession(id: String) {
        val session = _sessions.value.find { it.id == id }
        if (session != null) {
            _currentSession.value = session
            Log.d(TAG, "Switched to session ${session.id}: ${session.title}")
        } else {
            Log.w(TAG, "Session not found: $id")
        }
    }

    /**
     * Get a session by ID, or null if not found.
     */
    fun getSession(id: String): Session? {
        return _sessions.value.find { it.id == id }
    }

    /**
     * Mark the current session as completed with the given result message.
     */
    fun endCurrentSession(result: String) {
        val session = _currentSession.value ?: return
        session.isActive = false
        session.result = result
        session.lastActiveAt = System.currentTimeMillis()
        // Trigger recomposition by updating the list reference
        _sessions.value = _sessions.value.sortedByDescending { it.lastActiveAt }
        Log.d(TAG, "Ended session ${session.id}: $result")
    }

    /**
     * Delete a session by ID.
     */
    fun deleteSession(id: String) {
        _sessions.value = _sessions.value.filter { it.id != id }
        if (_currentSession.value?.id == id) {
            _currentSession.value = null
        }
        Log.d(TAG, "Deleted session $id")
    }

    /**
     * Clear all sessions.
     */
    fun clearAllSessions() {
        _sessions.value = emptyList()
        _currentSession.value = null
        Log.d(TAG, "Cleared all sessions")
    }

    /**
     * Add a debug log entry to the current session.
     */
    fun addEntryToCurrentSession(entry: DebugLogEntry) {
        val session = _currentSession.value ?: return
        session.debugEntries.add(entry)
        session.lastActiveAt = System.currentTimeMillis()
    }
}
