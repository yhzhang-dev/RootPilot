package com.hangge.rootpilot.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

data class AgentEvent(
    val id: Long,
    val type: String,
    val createdAt: Long,
    val data: String,
)

/** In-process event bus with a bounded replay window for desktop long polling. */
class AgentEventHub {
    private val nextId = AtomicLong(0)
    private val flow = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
    private val recent = ArrayDeque<AgentEvent>()

    fun publish(type: String, data: JSONObject = JSONObject()): AgentEvent {
        val event = AgentEvent(
            id = nextId.incrementAndGet(),
            type = type,
            createdAt = System.currentTimeMillis(),
            data = data.toString(),
        )
        synchronized(recent) {
            recent.addLast(event)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        flow.tryEmit(event)
        return event
    }

    suspend fun poll(afterId: Long, timeoutMs: Long): List<AgentEvent> {
        recentAfter(afterId).takeIf { it.isNotEmpty() }?.let { return it }
        withTimeoutOrNull(timeoutMs.coerceIn(0, 30_000)) {
            flow.first { it.id > afterId }
        }
        return recentAfter(afterId)
    }

    private fun recentAfter(afterId: Long): List<AgentEvent> = synchronized(recent) {
        recent.filter { it.id > afterId }.take(MAX_BATCH)
    }

    companion object {
        private const val MAX_RECENT = 256
        private const val MAX_BATCH = 64
    }
}
