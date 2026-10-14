package org.example

import kotlin.test.assertEquals

/** A root context with an empty source and sink, for testing contexts in isolation */
fun testRoot(record: Map<*, *> = emptyMap<Any?, Any?>(), sink: Sink = mutableListOf<Collector>().asSink()) =
    RootRecordContext(emptyList<Any>().asSource(), sink, Record.of(record), 0)

fun itemOf(value: Any?, root: RootRecordContext = testRoot()) = ItemContext(root, value)

/** Run a migration between plain collections, returning what was written to the sink */
fun runMigration(source: List<Map<*, *>>, block: RootRecordContext.() -> Unit): List<Map<Any?, Any?>> {
    val sink = mutableListOf<Map<Any?, Any?>>()
    migrate(source, sink, block)
    return sink
}

/** [assertEquals] without the generic-inference friction of comparing differently-typed collections */
fun check(expected: Any?, actual: Any?) = assertEquals(expected, actual)
