# Coming from the Ruby version

The concepts are the same (source, sink, record, item, take, put, collector, pipelines of transforms), but the API is
reshaped for Kotlin.

## Syntax

| Ruby | Kotlin |
|---|---|
| `Micdrop.migrate source, sink do ... end` | `migrate(source, sink) { ... }` |
| `take :a, put: "A"` | `take("a").put("A")` |
| `take :a, apply: pipeline, put: "A"` | `take("a").pipe(pipeline).put("A")` |
| `take :a, convert: ->(v) { v + 1 }` | `take("a").convert { (it as Int) + 1 }` |
| `take(:a) { ... }` | `take("a") { ... }` |
| `take(:a).default(0).put(:b)` | `take("a").default(0).put("b")` |
| `proc { default 0 }` | `val p: Pipeline = { default(0) }` |
| `apply default_0` | `pipe(defaultZero)` |
| `parse_date "%m/%d/%y"` | `parseDate("MM/dd/yy")` |
| `put_bury :a, :b` | `putBury("a", "b")` |

* The `apply` function from Ruby was renamed to `pipe` to avoid confusion with Kotlin's native `apply`
  context function.
* `take`, `put`, `apply` and `convert` options were dropped from `take` and friends in favor of blocks and chaining,
  which already did the same job.
* Function names are camelCase: `parse_int` is `parseInt`, `each_subrecord` is `eachSubrecord`, and so on.
  `parse_float` is `parseDouble`, since it produces a `Double`.
* Ruby symbols and strings are different keys; Kotlin has only strings (or whatever your source uses), so
  `take(:a)` becomes `take("a")`.
* Names can be anything with equality: `take(0)` for list positions, and so on.

## Behavior

* **`take` requires the item to exist.** Ruby's `take` returned nil for a missing key. Kotlin's `take` throws a
  `PipelineError` (an item that exists with a null value is fine), and `tryTake` is the lenient version. This catches
  typos in column names.
* **Dates and times use `java.time`.** Dates parse to `LocalDate`, date-times to `LocalDateTime`, and patterns are
  `DateTimeFormatter` patterns, not strftime. The default datetime format is still `yyyy-MM-dd HH:mm:ss`.
* **`zero_date` is gone.** Use `nullIf("0000-00-00")` before parsing, and `default(...)` when formatting.
* **Records go through the `Record` interface** rather than anything that responds to `[]`. Maps and lists work with
  no effort; other types need a [record adapter](extending.md#record-types).
* **Collectors are `MutableMap<Any?, Any?>`**, not "anything with `[]=`". Sinks may supply their own pre-filled map.
* **JSON** parses to plain Kotlin maps and lists: `Long` for integers and `Double` for the rest.
* **`lookup`**: registered lookups are referred to by name (`"sex"`, not `:sex`). The unknown-value options (
  `pass_if_not_found`, `on_not_found` and `warn_if_not_found`) are not present, use `pipeOrSetNull`, `pipeOrRevert`, 
  or `pipeOrFallback` instead.
* **`regex`** takes a `Regex`; named and numbered groups are takeable.
* **Hooks** receive the root context and the collector as arguments, as in Ruby, and several can be registered.
* **Sources and sinks close automatically**, and implement `AutoCloseable`. `Source.entries()` gives a `Sequence`
  instead of Ruby's `each`/`each_pair`/`each_with_index` duck-typing, and `Sink.write` replaces `<<`.
* **Transactions are on by default.** The Ruby sinks committed each statement as it went. A Kotlin migration is
  all-or-nothing: `migrate` commits the sink at the end and rolls it back on failure, and the database sinks hold
  their writes in a transaction accordingly. `transactional = false` gets the Ruby behavior back. See
  [Transactions](sources-and-sinks.md#transactions).
* **Databases use jOOQ instead of Sequel.** `Sequel` datasets as sources become `JooqSource` (any jOOQ query, or
  `JooqSource.table(db, "name")`); `InsertSink`, `UpdateSink` and `InsertUpdateSink` keep their names and roles,
  with `keys` as a list of strings and `UpdateAction` as an enum in place of the symbols (`:coalesce` is `Coalesce`,
  `:append_line` is `AppendLine`, and so on). `sink.insert_id` is `sink.lastInsertId`, and `was_insert` is
  `wasInsert`. Both are read from the sink object you hold rather than through `record.sink`. `db_lookup` is
  `dbLookup`, which can also cache.
* **HTML and XML use jsoup instead of Nokogiri.** Elements are records whose items are their attributes, as with a
  Nokogiri node's `[]`. `take_content`, `take_node_name`, `css`, `at_css`, `xpath` and `at_xpath` are `takeContent`,
  `takeNodeName`, `css`, `atCss`, `xpath` and `atXpath`, and what they return is an item, so `css(...)` gives a list
  to go over with `eachSubrecord`. Nokogiri's `parse_html` and `parse_html5` (and their fragment forms) become
  `parseHtml` and `parseHtmlFragment`, since jsoup is an HTML5 parser; `parse_xml` and `parse_xml_fragment` are
  `parseXml` and `parseXmlFragment`. The `encode_` and `decode_` entity functions keep their names minus the HTML5
  variants. See [HTML and XML](sources-and-sinks.md#html-and-xml).
* **`parseXml` is strict by default.** Nokogiri recovers from broken XML silently, as jsoup does. Kotlin's
  `parseXml` checks the text and throws a `ValueError`; pass `strict = false` to be lenient like Ruby.
* **XML can be streamed.** The Ruby version loaded the whole document, and used a `NodeSet` as the source. `XmlSource`
  streams a file with one record per element, in constant memory. For a document already loaded, `Elements.asSource()`
  does what a `NodeSet` did.
* **Skip and Stop** are `Throwable`s that aren't `Exception`s, and carry no stack trace.
* **Plain Kotlin collections** work as sources and sinks via `migrate(list, mutableList)`.

## Not (yet) ported

* Binary `pack`/`unpack`, `encode` and `force_encoding`
* The `send` transform (reflection-based)
* The Micro Focus DAT-file parser (left out on purpose)
* `StructureBuilder`'s fluent API (the `putBury` it powered is ported, as the internal `bury` function)

## New in the Kotlin version

* `pipeOrSetNull`, `pipeOrRevert` and `pipeOrFallback` for recovering from bad values
* `tryTake` and strict `take`
* `nullIf`, `nullIfEmpty`, `skipIf`, `stopIf` and friends
* `splitKv`/`joinKv` with a block form, `takeDig`/`extractDig`, `extract(range)`
* A CSV source and sink, and a pluggable `Record` abstraction
* All-or-nothing migrations: `commit` and `rollback` for sinks, and transactional database sinks by default
* Sharing one transaction between several sinks with `JooqSession`
* A streaming XML source, `XmlSource`
