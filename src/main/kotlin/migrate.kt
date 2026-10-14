package org.example

/**
 * Run [block] against every record in [from], writing the results to [to].
 *
 * Both are closed afterwards. When everything has been read, the source is closed and then [Sink.commit] is called.
 * If anything fails, [Sink.rollback] is called with the failure (which is then rethrown). Ending early with `stop()`
 * is not a failure.
 */
fun migrate(from: Source, to: Sink, block: RootRecordContext.() -> Unit) {
    to.use {
        try {
            from.use {
                for ((index, record) in from.entries()) {
                    val context = RootRecordContext(from, to, record, index)
                    try {
                        context.block()
                        context.flush(reset = false) // No need to reset; item processing is done
                    } catch (e: Skip) {
                        continue
                    } catch (e: Stop) {
                        break
                    }
                }
            }
            to.commit()
        } catch (e: Throwable) {
            try {
                to.rollback(e)
            } catch (rollbackFailure: Throwable) {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        }
    }
}

/** Convenience for migrating between plain collections */
fun migrate(from: Iterable<*>, to: MutableCollection<in Collector>, block: RootRecordContext.() -> Unit) =
    migrate(from.asSource(), to.asSink(), block)
