# Extending Micdrop

Everything here is meant to be done without touching the core library.

## Transforms

A transform is an extension function on `ItemContext` that updates `value` and returns the item. `transform` does the
usual work: it skips null values and stores the result.

```kotlin
fun ItemContext.subtract(amount: Int) = transform { (it as Int) - amount }

fun ItemContext.titleCase() = transform { text ->
    text.toString().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

migrate(source, sink) {
    take("a").subtract(1).put("A")
    take("name") {
        titleCase()
        put("name")
    }
}
```

Throw a `ValueError` when the value can't be handled; that is what `applyOrSetNull` and friends are for. If your
transform should also react to null (like `default` does), use `value` directly instead of `transform`:

```kotlin
fun ItemContext.orUnknown(): ItemContext {
    if (value == null) value = "unknown"
    return this
}
```

To enter a result as a record, as `parseJson` does, accept a block and call `enter`:

```kotlin
fun ItemContext.parseKeyValueLine(block: (SubRecordContext.() -> Unit)? = null): ItemContext {
    transform { line -> line.toString().split(";").associate { it.substringBefore("=") to it.substringAfter("=") } }
    if (block != null && value != null) enter(block)
    return this
}
```

Parsing JSON in `Json.kt` is a complete small example.

## Record types

`take` works through the `Record` interface:

```kotlin
interface Record {
    val raw: Any                          // the wrapped object
    operator fun contains(key: Any): Boolean
    operator fun get(key: Any): Any?      // null if absent
}
```

Maps, lists and regex matches are supported out of the box. To make another type takeable, register an adapter, which
turns an object into a `Record` or returns null if it doesn't recognize it:

```kotlin
class Person(val name: String, val age: Int)

Record.registerAdapter { value ->
    (value as? Person)?.let { person ->
        object : Record {
            override val raw = person
            override fun contains(key: Any) = key == "name" || key == "age"
            override fun get(key: Any): Any? = when (key) {
                "name" -> person.name
                "age" -> person.age
                else -> null
            }
        }
    }
}
```

Now any `Person` can be a record in a source, or entered from an item (`take("owner").take("name")`). Adapters
registered later win over earlier ones. This is how an XML or database-row type would be supported. Items that are
expensive to compute can be computed in `get`, which runs only when the item is taken; `FileRecord` does this for
file content.

## Sources

Implement `Source`: give it a lazy sequence of entries, and close whatever you opened.

```kotlin
class CounterSource(private val count: Int) : Source {
    override fun entries(): Sequence<SourceEntry> =
        (1..count).asSequence().map { SourceEntry(it, Record.of(mapOf("n" to it))) }
}
```

Return a `Sequence` built lazily (`sequence { }`, `.map`) so large inputs aren't loaded up front. Use whatever is
natural for the index: row number, key, file name. Throw `SourceError` for bad input.

If your source is stream-like, also take care that `close()` is safe to call whether or not iteration finished (see
`JsonLinesSource`).

## Sinks

Implement `Sink`. `makeCollector` is optional and creates the map `put` fills in; `write` receives each finished one.

```kotlin
class PrintSink : Sink {
    override fun write(collector: Collector) = println(collector)
}

class PrefilledSink : Sink {
    override fun makeCollector(): Collector = linkedMapOf("source" to "migration")
    override fun write(collector: Collector) { /* ... */ }
}
```

Throw `SinkError` for output that can't be written. `close()` is called when the migration ends.

A sink which can undo its writes should do so when the migration fails. `migrate` tells it how things went by calling
one of these, before `close()`:

* `commit()`: the migration finished (including ending early with `stop()`), after the source has been read and closed.
  Make everything written permanent.
* `rollback(cause)`: the migration failed because of `cause`, which is rethrown afterwards. Undo what was written.
  If this throws too, its exception is attached to `cause` as a suppressed exception.

Both do nothing by default. A sink which holds its writes in a transaction implements `commit` and `rollback`
to match, and rolls back in `close()` anything that wasn't committed (the database sinks do exactly this). If a
failure might leave `close()` with uncommitted work, it must discard it rather than keep it.

```kotlin
class BatchingSink(private val target: Target) : Sink {
    private val pending = mutableListOf<Collector>()
    override fun write(collector: Collector) { pending.add(collector) }
    override fun commit() { target.insertAll(pending); pending.clear() }
    override fun rollback(cause: Throwable) { pending.clear() }
}
```

`MultiSink` passes `commit`, `rollback` and `close` on to all of its sub-sinks, so wrapping sinks in one keeps them
working.

## Lookups

Register tables used across migrations:

```kotlin
ItemContext.registerLookup("states", mapOf("AL" to "Alabama", "AK" to "Alaska"))
```

## Errors

All of the library's own exceptions extend `MicdropException`:

| Exception | Meaning |
|---|---|
| `ValueError` | The value can't be converted, e.g. an unrecognized boolean. |
| `PipelineError` | The migration is wrong, e.g. taking a key that doesn't exist, or an unknown lookup name. |
| `SourceError` | The source's data is bad. |
| `SinkError` | The sink can't accept a record. |

`Skip` and `Stop` are separate: they are control flow (`ControlFlow`, a `Throwable` but not an `Exception`), so
catching `Exception` in your own code won't interfere with them.
