package org.example

/** A record context for a record nested inside an item. Output goes to the parent. */
class SubRecordContext(
    val itemContext: ItemContext,
    val parentContext: RecordContext,
) : RecordContext(itemContext.value?.let { Record.of(it) }), Output by parentContext {
    override val root: RootRecordContext get() = parentContext.root
}
