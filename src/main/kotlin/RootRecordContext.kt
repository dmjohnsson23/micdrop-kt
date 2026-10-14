package org.example

class RootRecordContext(
    val source: Source,
    val sink: Sink,
    val loopItem: Record,
    val loopIndex: Any?,
) : RecordContext(loopItem) {
    override val root: RootRecordContext get() = this

    var collector: Collector = sink.makeCollector()
        private set
    private var dirty = false
    private val beforeFlushHooks = mutableListOf<FlushHook>()
    private val afterFlushHooks = mutableListOf<FlushHook>()

    override fun put(name: Any, value: Any?) {
        collector[name] = value
        dirty = true
    }

    override fun putBury(keys: List<Any?>, value: Any?) {
        bury(collector, keys, value)
        dirty = true
    }

    override fun flush(reset: Boolean) {
        if (!dirty) return
        beforeFlushHooks.forEach { it(this, collector) }
        sink.write(collector)
        afterFlushHooks.forEach { it(this, collector) }
        if (reset) reset()
    }

    override fun reset() {
        dirty = false
        collector = sink.makeCollector()
    }

    override fun dumpCollector(prefix: String?) {
        if (prefix != null) println(prefix)
        println(collector)
        println()
    }

    override fun beforeFlush(hook: FlushHook) {
        beforeFlushHooks.add(hook)
    }

    override fun afterFlush(hook: FlushHook) {
        afterFlushHooks.add(hook)
    }
}
