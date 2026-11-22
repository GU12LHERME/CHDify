package com.conversorchd.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Estado da fila de conversão, compartilhado entre a interface e o serviço
 * (mesmo processo). Notificações de mudança são agrupadas e entregues na
 * thread principal no máximo a cada ~250 ms (4 quadros/s bastam para o
 * progresso e evitam gastar CPU redesenhando a tela durante a conversão).
 */
object JobRepository {
    private val lock = Any()
    private val jobs = ArrayList<Job>()
    private var idSeq = 1L
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)

    /** O serviço está processando a fila. */
    @Volatile var processing: Boolean = false
        set(value) { field = value; changed() }

    /** O usuário pediu para interromper a fila após o item atual (ou cancelou). */
    @Volatile var stopRequested: Boolean = false

    fun nextId(): Long = synchronized(lock) { idSeq++ }

    fun snapshot(): List<Job> = synchronized(lock) { jobs.toList() }

    fun get(id: Long): Job? = synchronized(lock) { jobs.firstOrNull { it.id == id } }

    fun add(list: List<Job>) {
        synchronized(lock) { jobs.addAll(list) }
        changed()
    }

    /** Remove um item que não esteja em andamento. */
    fun remove(id: Long): Job? {
        val removed = synchronized(lock) {
            val j = jobs.firstOrNull { it.id == id } ?: return null
            if (j.isActive) return null
            jobs.remove(j)
            j
        }
        changed()
        return removed
    }

    fun clearFinished(): List<Job> {
        val removed = synchronized(lock) {
            val r = jobs.filter { it.isFinished }
            jobs.removeAll(r.toSet())
            r
        }
        changed()
        return removed
    }

    /** Move um item não ativo uma posição para cima (-1) ou para baixo (+1). */
    fun move(id: Long, delta: Int) {
        synchronized(lock) {
            val i = jobs.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j < 0 || j >= jobs.size) return
            if (jobs[i].isActive || jobs[j].isActive) return
            val tmp = jobs[i]
            jobs[i] = jobs[j]
            jobs[j] = tmp
        }
        changed()
    }

    fun indexOf(id: Long): Int = synchronized(lock) { jobs.indexOfFirst { it.id == id } }

    fun size(): Int = synchronized(lock) { jobs.size }

    fun current(): Job? = synchronized(lock) { jobs.firstOrNull { it.isActive } }

    fun nextReady(): Job? = synchronized(lock) { jobs.firstOrNull { it.state == JobState.READY } }

    fun hasReady(): Boolean = nextReady() != null

    /** Recoloca itens cancelados/falhos na fila. */
    fun retry(id: Long) {
        synchronized(lock) {
            val j = jobs.firstOrNull { it.id == id } ?: return
            if (j.state == JobState.FAILED || j.state == JobState.CANCELLED) {
                j.state = if (j.analysis?.error == null) JobState.READY else JobState.BLOCKED
                j.message = null
                j.fraction = 0.0
                j.bytesDone = 0
                j.speedBps = 0.0
            }
        }
        changed()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    fun changed() {
        if (notifyPending.compareAndSet(false, true)) {
            main.postDelayed({
                notifyPending.set(false)
                listeners.forEach { it() }
            }, 250)
        }
    }
}
