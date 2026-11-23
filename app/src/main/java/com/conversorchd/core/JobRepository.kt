package com.conversorchd.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Playlist M3U de um conjunto multidisco: discos na ordem original. */
data class M3uGroup(val name: String, val jobIds: List<Long>)

/** Resumo do último lote processado (tela de conclusão). */
data class BatchSummary(
    val jobIds: List<Long>,
    val done: Int,
    val failed: Int,
    val cancelled: Int,
    val skipped: Int,
    val inputBytes: Long,
    val outputBytes: Long,
    val durationMs: Long,
)

/**
 * Estado da fila, compartilhado entre a interface e o serviço (mesmo processo).
 * Mudanças são agrupadas e entregues na thread principal no máximo a cada
 * ~250 ms. Mudanças estruturais e de estado são salvas em disco ([JobStore]).
 */
object JobRepository {
    private val lock = Any()
    private val jobs = ArrayList<Job>()
    private val groups = ArrayList<M3uGroup>()
    private var idSeq = 1L
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)
    private var appCtx: Context? = null

    /** O serviço está processando a fila. */
    @Volatile var processing: Boolean = false
        set(value) { field = value; changed() }

    /** Pedido para parar a fila inteira (o item atual é cancelado). */
    @Volatile var stopRequested: Boolean = false

    /** Itens do lote atual (para o progresso geral e o resumo). */
    @Volatile var batch: List<Long> = emptyList()
    @Volatile var batchStartedAt: Long = 0
    @Volatile var lastSummary: BatchSummary? = null

    /** Carrega a fila salva (chamado uma vez pelo App). */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (appCtx != null) return
            appCtx = ctx.applicationContext
            val loaded = JobStore.load(ctx)
            jobs.clear()
            jobs.addAll(loaded.jobs)
            idSeq = loaded.nextId
        }
    }

    fun nextId(): Long = synchronized(lock) { idSeq++ }

    fun snapshot(): List<Job> = synchronized(lock) { jobs.toList() }

    fun get(id: Long): Job? = synchronized(lock) { jobs.firstOrNull { it.id == id } }

    fun add(list: List<Job>, newGroups: List<M3uGroup> = emptyList()) {
        synchronized(lock) {
            jobs.addAll(list)
            groups.addAll(newGroups)
        }
        persist()
    }

    /**
     * Duplicata: mesmo documento, ou mesmo nome e tamanho, já presente na fila
     * para a mesma operação (itens finalizados não contam).
     */
    fun isDuplicate(file: InputFile, operation: Operation): Boolean = synchronized(lock) {
        jobs.any { j ->
            !j.isFinished && j.operation == operation &&
                (j.main.uri == file.uri || (j.main.name.equals(file.name, ignoreCase = true) && j.main.size == file.size && file.size > 0))
        }
    }

    /** Remove um item que não esteja em andamento. */
    fun remove(id: Long): Job? {
        val removed = synchronized(lock) {
            val j = jobs.firstOrNull { it.id == id } ?: return null
            if (j.isActive) return null
            jobs.remove(j)
            j
        }
        persist()
        return removed
    }

    fun clearFinished(): List<Job> {
        val removed = synchronized(lock) {
            val r = jobs.filter { it.isFinished && it.deleteState != DeleteState.PENDING_CONFIRM }
            jobs.removeAll(r.toSet())
            r
        }
        persist()
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
        persist()
    }

    fun current(): Job? = synchronized(lock) { jobs.firstOrNull { it.isActive } }

    fun nextReady(): Job? = synchronized(lock) { jobs.firstOrNull { it.state == JobState.READY } }

    fun hasReady(): Boolean = nextReady() != null

    /** Recoloca itens cancelados, falhos, ignorados ou interrompidos na fila. */
    fun retry(id: Long) {
        synchronized(lock) {
            val j = jobs.firstOrNull { it.id == id } ?: return
            if (j.state == JobState.FAILED || j.state == JobState.CANCELLED || j.state == JobState.INTERRUPTED || j.state == JobState.SKIPPED) {
                j.resetForRetry()
            }
        }
        persist()
    }

    /** Grupos M3U cujos discos terminaram todos com sucesso (cada grupo é devolvido uma vez). */
    fun completedGroups(): List<M3uGroup> = synchronized(lock) {
        val done = groups.filter { g ->
            g.jobIds.isNotEmpty() && g.jobIds.all { id -> jobs.firstOrNull { it.id == id }?.state == JobState.DONE }
        }
        groups.removeAll(done.toSet())
        done
    }

    /** Outros itens da fila que usam o mesmo documento (arquivo compartilhado). */
    fun jobsReferencing(uri: Uri, except: Long): List<Job> = synchronized(lock) {
        jobs.filter { j -> j.id != except && j.inputFiles.any { it.uri == uri } }
    }

    // ------------------------------------------------------------------ lote

    fun startBatch() {
        synchronized(lock) {
            batch = jobs.filter { it.state == JobState.READY }.map { it.id }
            batchStartedAt = SystemClock.elapsedRealtime()
            lastSummary = null
        }
        changed()
    }

    /** Progresso geral do lote (0..1), ponderado pelo tamanho de entrada. */
    fun batchProgress(): Double = synchronized(lock) {
        val items = batch.mapNotNull { id -> jobs.firstOrNull { it.id == id } }
        val total = items.sumOf { maxOf(1L, it.inputBytes) }.toDouble()
        if (total <= 0) return 0.0
        val done = items.sumOf { j ->
            when {
                j.isFinished -> maxOf(1L, j.inputBytes).toDouble()
                j.isActive -> maxOf(1L, j.inputBytes) * j.fraction
                else -> 0.0
            }
        }
        done / total
    }

    fun finishBatch() {
        synchronized(lock) {
            val items = batch.mapNotNull { id -> jobs.firstOrNull { it.id == id } }
            if (items.isNotEmpty()) {
                val ok = items.filter { it.state == JobState.DONE }
                lastSummary = BatchSummary(
                    items.map { it.id },
                    ok.size,
                    items.count { it.state == JobState.FAILED },
                    items.count { it.state == JobState.CANCELLED || it.state == JobState.READY },
                    items.count { it.state == JobState.SKIPPED },
                    ok.sumOf { it.inputBytes },
                    ok.sumOf { it.outputBytes },
                    SystemClock.elapsedRealtime() - batchStartedAt,
                )
            }
        }
        persist()
    }

    fun dismissSummary() {
        lastSummary = null
        changed()
    }

    // ------------------------------------------------------------------ notificação e persistência

    fun addListener(l: () -> Unit) { listeners.add(l) }

    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Mudança transitória (progresso): só redesenha. */
    fun changed() {
        if (notifyPending.compareAndSet(false, true)) {
            main.postDelayed({
                notifyPending.set(false)
                listeners.forEach { it() }
            }, 250)
        }
    }

    /** Mudança de estado ou estrutura: salva a fila e redesenha. */
    fun persist() {
        val ctx = appCtx
        if (ctx != null) {
            val (list, next) = synchronized(lock) { jobs.toList() to idSeq }
            JobStore.save(ctx, list, next)
        }
        changed()
    }
}
