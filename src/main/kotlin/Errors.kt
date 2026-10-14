package org.example

/**
 * Marks the micdrop DSL receivers, so a nested block can't accidentally call methods of an outer receiver.
 * Anything intended to be callable from inside an item block is forwarded explicitly on [ItemContext].
 */
@DslMarker
annotation class MicdropDsl

open class MicdropException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** An error on the sink side of the migration */
class SinkError(message: String?, cause: Throwable? = null) : MicdropException(message, cause)

/** An error on the source side of the migration */
class SourceError(message: String?, cause: Throwable? = null) : MicdropException(message, cause)

/** An error with the current data value that prevents conversion operations from working */
class ValueError(message: String?, cause: Throwable? = null) : MicdropException(message, cause)

/** An error with the data pipeline itself */
class PipelineError(message: String?, cause: Throwable? = null) : MicdropException(message, cause)

/**
 * Base for [Skip] and [Stop]. These are deliberately not [Exception]s, so `catch (e: Exception)` doesn't swallow
 * them, and they carry no stack trace since they are used for ordinary control flow.
 */
abstract class ControlFlow : Throwable() {
    override fun fillInStackTrace(): Throwable = this
}
