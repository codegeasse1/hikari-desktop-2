package com.lagradost.cloudstream3.utils

open class AtomicList<T>(private val delegate: List<T> = emptyList()) : List<T> {
    private val lock = Any()

    fun <R> withLock(block: () -> R): R = synchronized(lock) { block() }

    fun snapshot(): List<T> = synchronized(lock) { delegate.toList() }

    suspend fun <R> withLockSuspend(block: suspend () -> R): R = block()

    fun filter(predicate: (T) -> Boolean): AtomicList<T> = synchronized(lock) { AtomicList(delegate.filter(predicate)) }
    fun distinctBy(selector: (T) -> Any?): AtomicList<T> = synchronized(lock) { AtomicList(delegate.distinctBy(selector)) }

    override val size: Int get() = synchronized(lock) { delegate.size }
    override fun isEmpty(): Boolean = synchronized(lock) { delegate.isEmpty() }
    override fun contains(element: T): Boolean = synchronized(lock) { delegate.contains(element) }
    override fun containsAll(elements: Collection<T>): Boolean = synchronized(lock) { delegate.containsAll(elements) }
    override fun get(index: Int): T = synchronized(lock) { delegate[index] }
    override fun indexOf(element: T): Int = synchronized(lock) { delegate.indexOf(element) }
    override fun lastIndexOf(element: T): Int = synchronized(lock) { delegate.lastIndexOf(element) }

    override fun iterator(): Iterator<T> = delegate.iterator()
    override fun listIterator(): ListIterator<T> = delegate.listIterator()
    override fun listIterator(index: Int): ListIterator<T> = delegate.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): List<T> = delegate.subList(fromIndex, toIndex)

    operator fun plus(element: T): AtomicList<T> = synchronized(lock) { AtomicList(delegate + element) }

    operator fun plus(elements: Collection<T>): AtomicList<T> = synchronized(lock) { AtomicList(delegate + elements) }
}

class AtomicMutableList<T>(
    private val mutableDelegate: MutableList<T> = mutableListOf(),
) : AtomicList<T>(mutableDelegate), MutableList<T> {
    private val lock = Any()

    override fun add(element: T): Boolean = synchronized(lock) { mutableDelegate.add(element) }
    override fun add(index: Int, element: T) = synchronized(lock) { mutableDelegate.add(index, element) }
    override fun addAll(elements: Collection<T>): Boolean = synchronized(lock) { mutableDelegate.addAll(elements) }
    override fun addAll(index: Int, elements: Collection<T>): Boolean = synchronized(lock) { mutableDelegate.addAll(index, elements) }
    override fun remove(element: T): Boolean = synchronized(lock) { mutableDelegate.remove(element) }
    override fun removeAt(index: Int): T = synchronized(lock) { mutableDelegate.removeAt(index) }
    override fun removeAll(elements: Collection<T>): Boolean = synchronized(lock) { mutableDelegate.removeAll(elements) }
    override fun retainAll(elements: Collection<T>): Boolean = synchronized(lock) { mutableDelegate.retainAll(elements) }
    override fun set(index: Int, element: T): T = synchronized(lock) { mutableDelegate.set(index, element) }
    override fun clear() = synchronized(lock) { mutableDelegate.clear() }

    override fun iterator(): MutableIterator<T> = mutableDelegate.iterator()
    override fun listIterator(): MutableListIterator<T> = mutableDelegate.listIterator()
    override fun listIterator(index: Int): MutableListIterator<T> = mutableDelegate.listIterator(index)
    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = mutableDelegate.subList(fromIndex, toIndex)
}
