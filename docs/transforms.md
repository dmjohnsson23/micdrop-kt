# Transform reference

Everything available inside a migration. Names are listed with their parameters; see the KDoc for finer detail.

A migration block (`migrate(from, to) { ... }`) runs once per source record with a `RootRecordContext` as its
receiver. `take` and friends return an `ItemContext`, which is what you build pipelines on.

Unless noted otherwise, every transform:

* leaves a `null` value as `null`, and
* returns the `ItemContext` itself, so calls can be chained.

## In a record context

These work directly in a migration block, and in the block of `enter`, `parseJson`, `split`, `splitKv`, `regex` and
`eachSubrecord` (which are sub-record contexts). Every one that returns an item takes an optional trailing pipeline
block.

| Function | Description |
|---|---|
| `take(name)` | Take an item. It must exist (`PipelineError` otherwise), but its value may be null. |
| `takeDig(vararg names)` | Take from nested records. Every key on the way must exist; a null along the way gives null. |
| `tryTake(name)` | Take an item, or null if it doesn't exist. |
| `takeWhole()` | Take the whole record as one item. |
| `static(value)` | An item with a value you supply. |
| `index()` | The record's index: row number, map key, file name, etc. |
| `passthru(vararg names)` | `take(name).put(name)` for each name. |
| `collectList(vararg items)` | An item whose value is the list of the given items' values. |
| `collectKv(vararg name to item)` or `collectKv(map)` | An item whose value is a map of name to the item's value. |
| `collectFormatString(template, vararg items)` | An item whose value is `template.format(...)` of the items' values (nulls become empty text). |
| `eachSubrecord(flush, reset) { }` | Shorthand for `takeWhole().eachSubrecord(...)`. |
| `skip()` | Abandon this source record. |
| `stop()` | End the migration. |

### Output

Also available in any record context; sub-record contexts forward to the root.

| Function | Description |
|---|---|
| `put(name, value)` | Put a value in the output record. |
| `putBury(keys, value)` | Put a value in nested structure. (Items use `putBury(vararg keys)`.) |
| `flush(reset = true)` | Write the output record to the sink, then clear it if `reset`. Does nothing if nothing has been put. |
| `reset()` | Clear the output record. |
| `dumpCollector(prefix)` | Print the output record. |
| `beforeFlush { root, collector -> }` | Run before each write to the sink. |
| `afterFlush { root, collector -> }` | Run after each write to the sink. |

The root context also exposes `source`, `sink`, `loopItem` (the source record), `loopIndex` and `collector`.

## On an item

### Pipeline control

| Function | Description |
|---|---|
| `value`, `originalValue` | The current value (a `var`), and the value the item started with. |
| `update(value)` | Replace the value. |
| `convert { value -> }` | Replace the value with the result of a Kotlin function. Also receives null. |
| `pipe(pipeline)` | Run a stored `Pipeline` on the item. |
| `pipeOrSetNull(warn, pipeline)` | Run it; if it throws an `Exception`, the value becomes null. |
| `pipeOrRevert(warn, pipeline)` | Run it; if it throws, restore the previous value. |
| `pipeOrFallback(pipeline, fallback, warn)` | Run it; if it throws, restore the previous value and run `fallback`. All three print the `warn` message to stderr, followed by the error, when the pipeline fails (if `warn` is given). |
| `dump(prefix)` | Print the value. |
| `transform { value -> }` | Building block for your own transforms: replaces a non-null value with the result. |

`Skip` and `Stop` (from `skip()` and `stop()`) are not `Exception`s and are never caught by the `pipeOr...` family.

### Navigation

| Function | Description |
|---|---|
| `enter { }` | Treat the value as a record; the block can `take` from it. Returns the sub-record context. |
| `take(name)`, `takeDig(...)`, `tryTake(name)` | `scope().enter().take(...)`: take from the value as a record, without disturbing this item. |
| `scope { }` | A copy of this item to experiment on, leaving this one unchanged. Returns the copy. |
| `extract(name)` | Replace the value with the named item of itself (which must exist). |
| `extract(range)` | Replace a list with a slice of itself. |
| `extractDig(vararg names)` | Extract from nested records. |

### Putting

| Function | Description |
|---|---|
| `put(name)` | Put the value in the output record. |
| `put(name, value)` | Put a different value in the output record (this item's value is not used). |
| `putBury(vararg keys)` | Put the value in nested structure. A `null` key appends to a list; an `Int` indexes a list (negative from the end); anything else is a map key. Missing levels are created. |

`static`, `index`, `collectList`, `collectKv`, `collectFormatString`, `skip`, `stop`, `flush` and `reset` are also
available on an item, so they can be used inside a `take` block.

### Parsing and formatting

| Function | Description |
|---|---|
| `parseInt(radix = 10)` | To `Int`. Numbers are converted; text must be a valid integer. |
| `parseDouble()` | To `Double`. |
| `parseDate(format)` | To `LocalDate`. `format` is a `DateTimeFormatter` or a pattern string; the default is ISO (`2024-11-14`). Accepts existing dates and date-times. |
| `parseDateTime(format)` | To `LocalDateTime`. The default format is `yyyy-MM-dd HH:mm:ss`. A `LocalDate` becomes midnight. |
| `formatDate(format)` | Date, date-time (or any `TemporalAccessor`) to text. The default is ISO date. |
| `formatDateTime(format)` | As above; the default is `yyyy-MM-dd HH:mm:ss`. A `LocalDate` is formatted as midnight. |
| `parseBoolean(trueValues, falseValues)` | To `Boolean`. By default accepts `1`, `true`, `yes`, `on`, `y` (several capitalizations) as true, and `0`, `false`, `no`, `off`, `n` and the empty string as false. Anything else is a `ValueError`. |
| `formatBoolean(trueValue = "Yes", falseValue = "No")` | Boolean to something else. |
| `formatString(template)` | `String.format(template, value)`, or `toString()` with no template. |
| `parseJson { }` | Text to maps, lists, strings, `Long`s, `Double`s, booleans and null. The optional block enters the result as a record. Invalid JSON is a `ValueError`. |
| `formatJson()` | To compact JSON text. Unknown types (dates, for instance) are written as strings. |

All date patterns are Java's, not strftime's: `MM/dd/yy`, not `%m/%d/%y`.

### Common operations

| Function | Description                                                                                                                                                                                                                                                             |
|---|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `lookup(map)` | Convert through a map. Unknown values case an error.                                                                                                                                                                                                                    |
| `lookup(name)` | The same with a lookup registered by `ItemContext.registerLookup(name, map)`.                                                                                                                                                                                           |
| `dbLookup(db, table, keyColumn, valueColumn, ...)` | Convert through a query on a database table (needs [jOOQ](sources-and-sinks.md#databases); in `org.example.ext.jooq`). An unknown value causes an error, like `lookup`. Also takes a `DbLookup` in place of the first four, for caching. |
| `stringReplace(find, replace)` | Replace in text; `find` can be a `String` or a `Regex`.                                                                                                                                                                                                                 |
| `strip()` | Trim whitespace from text.                                                                                                                                                                                                                                              |
| `default(value)` | Replace null with a value.                                                                                                                                                                                                                                              |
| `nullIfEmpty()` | Empty text, lists and maps become null.                                                                                                                                                                                                                                 |
| `nullIf(sentinel)` | A known value becomes null.                                                                                                                                                                                                                                             |
| `skipIfEmpty()`, `skipIf(sentinel)` | Skip the record.                                                                                                                                                                                                                                                        |
| `stopIfEmpty()`, `stopIf(sentinel)` | Stop the migration.                                                                                                                                                                                                                                                     |

### Text and lists

| Function | Description |
|---|---|
| `split(delimiter) { }` | Text to a list. The optional block enters the list as a record (`take(0)`...). |
| `join(delimiter)` | List to text. Null elements count as empty text. |
| `splitKv(kvDelimiter, itemDelimiter = "\n") { }` | Text such as `a: 1\nb: 2` to a map. Splits each item at the first `kvDelimiter`; blank items are ignored; an item with no delimiter has a null value. The optional block enters the map. |
| `joinKv(kvDelimiter, itemDelimiter = "\n")` | The reverse. |
| `regex(pattern) { }` | Run `Regex.find`; the value becomes the match (or null, with a warning). The optional block enters the match, where groups are takeable by number (0 is the whole match) or by name. |
| `coalesce()` | List to its first non-null element. |
| `compact()` | Remove nulls from a list, or the entries with null values from a map. |
| `filter { }` | Keep list elements matching a predicate. |
| `map { }` | Transform each list element with a Kotlin function. |
| `mapApply { }` | Transform each list element with a pipeline. |
| `eachSubrecord(flush, reset) { }` | Run a block in a sub-record context for each element of a list, optionally flushing and/or resetting after each. |

## HTML and XML (extension)

In `org.example.ext.jsoup`; needs [jsoup](https://jsoup.org) on the classpath. See
[the guide](sources-and-sinks.md#html-and-xml) for the details.

On an item:

| Function | Description |
|---|---|
| `parseXml(strict = true) { }` | Text to an XML document. `strict` rejects XML which isn't well-formed (a `ValueError`). The optional block enters the document as a record. |
| `parseXmlFragment(strict = true) { }` | Text with several top-level XML elements, to a document. |
| `parseHtml { }` | Text to an HTML document, parsed like a browser would. |
| `parseHtmlFragment { }` | Text to the `body` element holding a piece of HTML. |
| `decodeHtml()`, `encodeHtml(nl2br = false)` | Convert HTML entities to characters, and back. |
| `decodeXml()`, `encodeXml()` | Convert XML entities to characters, and back. |

On an element, in a record context or on an item holding one (elements have their attributes as items, so
`take("id")` reads an attribute). Each takes an optional trailing pipeline block:

| Function | Description |
|---|---|
| `css(selector)` | All elements inside which match the CSS selector, as a list. |
| `atCss(selector)` | The first match, or null. |
| `xpath(expression)` | All elements inside which match the XPath expression, as a list. |
| `atXpath(expression)` | The first match, or null. |
| `takeContent()` | The text of the element, and everything inside it, as written. |
| `takeNodeName()` | The name of the element. |
