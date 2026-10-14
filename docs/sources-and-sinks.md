# Sources and sinks

`migrate(from, to) { ... }` takes a `Source` and a `Sink`, and closes both when it finishes (even if it fails).
For quick jobs, `migrate(iterable, mutableCollection) { ... }` wraps plain collections for you.

## Sources

A source produces `SourceEntry(index, record)` values lazily. Each record is a `Record`: a uniform view of a map, a
list, a regex match, a file, or whatever else has items to take. All the built-in sources below can be opened over a
`Reader`/`Path`, and are read once.

### Collections

```kotlin
listOf(mapOf("a" to 1)).asSource()      // indexed 0, 1, 2...
sequenceOf(mapOf("a" to 1)).asSource()
mapOf("x" to mapOf("a" to 1)).asSource() // indexed by key
```

Elements can be maps or lists (or anything with a [record adapter](extending.md#record-types)).

### CSV: `CsvSource`

```kotlin
CsvSource.open(Path.of("people.csv"))
CsvSource(reader, delimiter = '\t')   // TSV
```

Each record is a `Map<String, String?>` keyed by column header, so `take("Name")`. Records are indexed from 0.

| Option | Meaning |
|---|---|
| `headers` | Column names to use. |
| `firstRowIsHeader` (default true) | Whether the first row is a header row. With `headers` given, it is skipped and `headers` replaces it. With `firstRowIsHeader = false` and no `headers`, records are lists, taken by position (`take(0)`). |
| `delimiter`, `quote` | The field delimiter and quote characters. |
| `trim` | Strip whitespace around unquoted fields. |
| `closeReader` | Whether closing the source closes the reader. |
| `charset` | For `CsvSource.open` only. |

Behavior:

* An unquoted empty field is `null`; a quoted `""` is an empty string.
* Short rows are padded with null. Extra fields beyond the headers are a `SourceError`, unless they are empty.
* Blank lines are skipped, and a UTF-8 byte order mark (as Excel writes) is ignored.
* If a header name repeats, the first column with that name wins.
* Values are always text; use `parseInt`, `parseDate` and so on.
* Malformed quoting is a `SourceError` with the row number.

### JSON-Lines: `JsonLinesSource`

One JSON object (or array) per line.

```kotlin
JsonLinesSource.open(Path.of("data.jsonl"))
JsonLinesSource(reader)
JsonLinesSource.fromCommand("some-tool", "--export")  // reads the command's standard output
```

Records are plain maps and lists. Numbers are `Long` or `Double`. Blank lines are skipped; a line that isn't valid
JSON, or isn't an object or array, is a `SourceError` naming the line. `fromCommand` waits for the process on close,
and fails if it exited with an error status after all its output was read (a deliberate `stop()` is not an error).

### Files: `FilesSource`

Iterates files rather than their contents, with one record per file, indexed by relative file name.

```kotlin
FilesSource(Path.of("data"))                                   // everything directly in data/
FilesSource("data", files = listOf("a.json", "b.json"))
FilesSource("data", glob = "**.json")                          // recursive
```

`glob` uses [Java's glob syntax](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/FileSystem.html#getPathMatcher(java.lang.String)):
`*` (within a directory), `**` (across directories), `?`, `[0-9]`, `{a,b}`. Results are sorted. Anything that isn't
a regular file is skipped with a warning.

A file record offers these items to `take`:

| Item | Value |
|---|---|
| `content` | The text (decoded with `charset`, default UTF-8). Only read if you take it. |
| `bytes` | The raw bytes. |
| `stream` | An open `InputStream`. You must close it. |
| `filename` | The path as built from the directory, e.g. `data/x.json`. |
| `basename` | `x.json` |
| `extension` | `json` |
| `path` | The absolute path. |
| `size` | Size in bytes. |
| `mtime`, `ctime`, `atime` | Modified, created and accessed times as `Instant`s. |

```kotlin
migrate(FilesSource("data", glob = "*.json"), sink) {
    take("basename").put("file")
    take("content").parseJson {
        take("name").put("name")
    }
}
```

## Sinks

A sink makes a fresh collector (by default a `LinkedHashMap`) for each output record, and then receives it in
`write` after the migration has filled it in with `put`. When the migration is over it is told whether to `commit`
(it finished, or was ended early by `stop()`) or `rollback` (it failed), and then it is closed. Most sinks have
nothing to undo and ignore this; [databases](#databases) use it to make a migration all-or-nothing.

### Collections

```kotlin
val sink = mutableListOf<Collector>()
migrate(source, sink) { ... }       // or migrate(source.asSource(), sink.asSink())
```

### CSV: `CsvSink`

```kotlin
CsvSink.open(Path.of("out.csv"))
CsvSink(writer, headers = listOf("name", "age"))
```

Rows are streamed, so the columns are fixed from the first record (or from `headers`). A record with a key that isn't
a column is a `SinkError`; a record missing a column writes it empty. Maps and lists are a `SinkError` too; convert
them first, for example with `formatJson()`.

| Option | Meaning |
|---|---|
| `headers` | The columns, in order. Otherwise they are the first record's keys. |
| `writeHeader` (default true) | Whether to write the header row. With explicit `headers`, it is written even if there are no records. |
| `delimiter`, `quote` | As for the source. |
| `lineSeparator` | Default `"\n"`. |
| `byteOrderMark` | Start with a UTF-8 BOM, which Excel needs to detect UTF-8. |
| `closeWriter` | Whether closing the sink closes the writer. It is flushed either way. |
| `charset` | For `CsvSink.open` only. |

`null` is written as an empty field and `""` as a quoted `""`, so a `CsvSource` reads back exactly what was written.
Values are written with `toString()`, which suits text, numbers, booleans and dates.

### JSON-Lines: `JsonLinesSink`

```kotlin
JsonLinesSink.open(Path.of("out.jsonl"))
JsonLinesSink(writer, closeWriter = false)
```

One compact JSON object per line. Nested maps and lists are written as nested JSON, so `putBury` works well here.

### Several sinks: `MultiSink`

```kotlin
MultiSink("person" to peopleSink, "address" to addressSink)
```

Records are built with `putBury("person", "name")` and so on, and each part is sent to its sink. Parts that stayed
empty are skipped (`ignoreEmptySubs = false` to keep them). Closing it closes every sub-sink.

## HTML and XML

HTML and XML are supported through [jsoup](https://jsoup.org). It is an optional dependency: add it to your own project
(`implementation("org.jsoup:jsoup:<version>")`), and nothing else needs it. Everything is in `org.example.ext.jsoup`.

In this extension an element is a record whose items are its **attributes**, so `take("id")` reads `id="..."`. To get
at what is inside an element, search it: `css`, `atCss`, `xpath`, `atXpath`. Both HTML and XML elements work the same
way. jsoup provides the elements as `org.jsoup.nodes.Element`s (a whole document is one too), so jsoup's own API is
there if you need it.

### Streaming a file: `XmlSource`

```kotlin
XmlSource.open(Path.of("catalog.xml"), "catalog/product")
XmlSource(inputStream, "product")       // from a stream, closing it when done
XmlSource(reader, "/feed/entry", closeInput = false)
```

Reads an XML document as a stream and makes a record of each element selected by the path, so memory use stays small
whatever the size of the file (jsoup and DOM parsers need many times a file's size in memory; as a rough idea, a 30 MB
file with 150,000 records streams in about 3 seconds). Only one record exists at a time, and records are indexed from 0.

The path says which elements are records, by name (namespace prefixes are ignored):

| Path | Matches |
|---|---|
| `product` | any `product`, at any depth |
| `catalog/product` | any `product` directly inside a `catalog`, wherever that is |
| `/catalog/product` | only the `product`s directly inside the root `catalog` |
| `/catalog` | only the root element itself, as a single record |

An element inside a record is part of it, even if its name matches too. The namespace declarations in force where the
element appears are carried into the record, so its prefixes still work. The encoding comes from the file's XML
declaration (UTF-8 if there is none). A byte order mark is handled.

Streaming reads strictly. A document that isn't well-formed is a `SourceError` giving the line and column, after the
records before the error have been delivered. DTDs are ignored and external entities are never fetched, so an
`<!ENTITY ... SYSTEM "file:///...">` can't be used to read your files.

### Parsing markup in a value

The `parse...` transforms turn text into elements, and (like `parseJson`) take an optional block which runs with the
result as a record. They do nothing to null.

| Transform | Result |
|---|---|
| `parseXml(strict = true) { }` | An XML document. |
| `parseXmlFragment(strict = true) { }` | A piece of XML with several top-level elements, as a document. |
| `parseHtml { }` | An HTML document, parsed the way a browser would, so even broken HTML gets `html` and `body`. |
| `parseHtmlFragment { }` | A piece of HTML. The value is the `body` element holding it. |

**Strict parsing.** jsoup quietly accepts XML that isn't well-formed (`<a><b></a>` parses without complaint), which in a
migration means silently migrating part of a damaged file. So `parseXml` first checks the text and throws a `ValueError`
if it isn't well-formed. Pass `strict = false` to be lenient. HTML is always lenient: that is what HTML parsing is.

jsoup parses HTML5, which is a superset of what the Ruby version's separate HTML and HTML5 methods offered, so there is
one set of HTML functions here.

For whole documents outside a pipeline there are `parseXmlDocument(text, strict)` and `parseHtmlDocument(text)`, and
`Elements.asSource()` makes a source out of the result of a jsoup query:

```kotlin
val document = parseXmlDocument(Path.of("small.xml").readText())
migrate(document.select("product").asSource(), sink) { ... }
```

### Looking inside elements

These work inside the block of `parseXml` and friends, in an `XmlSource` migration, and on an item holding an element.
Each can take a trailing pipeline block, and returns an item.

| Function | Gives |
|---|---|
| `css(selector)` | All the elements inside which match a CSS selector, as a list. Go over them with `eachSubrecord`. |
| `atCss(selector)` | The first match, or null. |
| `xpath(expression)` | All the elements inside which match an XPath expression. |
| `atXpath(expression)` | The first match, or null. |
| `takeContent()` | The text of the element and everything inside it, exactly as written (whitespace included). |
| `takeNodeName()` | The element's name, e.g. `price`. |

A selector which doesn't parse is a `PipelineError`. A search with no match gives an empty list or null; `takeContent`
on that null is null as well, so a missing element doesn't need special handling. Things to know:

* Only elements can be found, not attributes or text. Use `take("name")` for attributes, and `takeContent()` for text.
* jsoup's `select` also matches the element it is called on, if the selector fits it.
* Tag selectors ignore case, though names keep their case. In XML, select a namespaced element with `prefix|name`.
* `xpath` is slower than `css` in jsoup, since it converts the document each time. Prefer CSS in hot paths.

### Entities

| Transform | Does |
|---|---|
| `decodeHtml()` | Turns HTML entities (`&lt;`, `&copy;`, `&#169;`) into characters. |
| `encodeHtml(nl2br = false)` | Escapes `<`, `>` and `&`. With `nl2br`, line breaks become `<br/>`. |
| `decodeXml()` | Turns the XML entities (`&lt;`, `&gt;`, `&amp;`, `&quot;`, `&apos;`) and numeric ones into characters. |
| `encodeXml()` | Escapes `<`, `>` and `&`. |

Whitespace is left exactly as it is.

## Databases

Databases are supported through [jOOQ](https://www.jooq.org), which builds the SQL (quoting, dialect differences)
so that Micdrop doesn't have to. It is an optional dependency: add jOOQ and a JDBC driver to your own project
(`implementation("org.jooq:jooq:<version>")`), and nothing else needs it.

Everything is untyped, like the rest of Micdrop: tables and columns are strings, rows are maps, and no table
definitions or code generation are needed. Everything is in `org.example.ext.jooq`.

```kotlin
val db = DSL.using(dataSource, SQLDialect.MYSQL)        // or DSL.using(connection, SQLDialect.SQLITE), etc.
```

### Reading: `JooqSource`

```kotlin
JooqSource.table(db, "people")                          // a whole table
JooqSource(db.selectFrom(DSL.table(DSL.name("people"))).where(DSL.field(DSL.name("status")).eq("alive")))
JooqSource(db.resultQuery("select * from people where status = ?", "alive"))   // plain SQL
```

Any jOOQ query works, including joins. Each row is a `Map<String, Any?>` keyed by column name (or alias), so
`take("first_name")`, and records are indexed by row number from 0. Values are what the driver gives, except that the
legacy `java.sql.Date`, `Time` and `Timestamp` become `LocalDate`, `LocalTime` and `LocalDateTime`, so `parseDate`,
`formatDate` and the others work on them.

Rows are fetched lazily. `fetchSize` is a hint to the driver of how many rows to fetch at a time.

**On MySQL**, the driver reads an entire result into memory unless told otherwise, which is no good for large
tables. Pass `fetchSize = Int.MIN_VALUE` to stream rows one at a time (or add `useCursorFetch=true` to the connection
URL and use a positive size). A streaming result keeps its connection busy until it has all been read, so the
migration's sink must not use the same connection. Make the `DSLContext` from a `DataSource` (as above), which gives
the sink a connection of its own.

### Writing: `InsertSink`, `UpdateSink`, `InsertUpdateSink`

All three take a table name, and use each record's keys as column names. Each can be built from a `DSLContext`, or from
a [`JooqSession`](#transactions). A failed statement (an unknown column, a constraint violation) is a `SinkError`.

| Sink | Does |
|---|---|
| `InsertSink(db, table, idColumn)` | Inserts every record as a new row. |
| `UpdateSink(db, table, keys, matchNullKeys)` | Updates the row(s) where the `keys` columns equal the record's values for them. A record with a null or missing key is a `SinkError`, unless `matchNullKeys`, which makes null match rows where that column is null. `lastRowsUpdated` tells how many rows the last record changed; a record that matches nothing does nothing. |
| `InsertUpdateSink(db, table, keys, updateActions, defaultUpdateAction, matchNullKeys, idColumn)` | Looks for the row identified by `keys`; updates it if it is there, inserts a new row if not. More than one match is a `SinkError`. A null or missing key can't identify a row, so the record is inserted (or with `matchNullKeys`, matches nulls). |

`InsertUpdateSink` reads the existing row first, so it works the same on every database, and so it can combine old and
new values. `updateActions` maps column names to an `UpdateAction`, and `defaultUpdateAction` covers the others:

| `UpdateAction` | Result when the row exists |
|---|---|
| `Coalesce` (default) | The new value, unless it is null, in which case the old one stays. |
| `OverwriteNulls` | The new value only if the old one is null. |
| `AlwaysOverwrite` | The new value, even null. |
| `KeepExisting` | The old value. |
| `Append` / `AppendLine` | Old text, then the new, separated by a space / a newline. |
| `Prepend` / `PrependLine` | New text, then the old, separated by a space / a newline. |
| `Add` | The sum of the old and new numbers. |

Only the columns a record contains are touched, and only if their value changes. Null and empty text count as
nothing when combining, so `Append` doesn't leave a stray separator. `Add` on something that isn't a number is a
`SinkError`.

`InsertSink` and `InsertUpdateSink` have `lastInsertId`, for reading in an `afterFlush` hook (`wasInsert` tells
whether `InsertUpdateSink` inserted or updated; `lastInsertId` is null for an update). It is the ID the database
generated for the row, found by asking it (`LAST_INSERT_ID()` on MySQL), which needs an auto-increment column. If you
supply the ID yourself, name its column as `idColumn` and the value you gave is reported instead. This matters on
MySQL, where a supplied ID isn't reported as generated.

### Transactions

Database sinks hold their writes in a transaction by default, which `migrate` commits when the migration finishes
and rolls back when it fails. Either everything is written or nothing is. `stop()` counts as finishing.

The transaction belongs to a `JooqSession`, which takes a connection from your `DSLContext` the first time it is
needed (so from a `DataSource`'s pool, if that is what the context uses), turns auto-commit off on it, and gives
everything back when the sink is closed. Closing without a commit discards the writes. You can use one directly:

```kotlin
val session = JooqSession(db)
val sink = MultiSink(
    "person" to InsertSink(session, "people"),
    "phone" to InsertSink(session, "phones"),
)
migrate(source, sink) { ... }     // both tables, or neither
```

A sink given a `DSLContext` instead makes a session of its own, so sinks made that way have separate transactions:
use a shared session when several sinks must succeed or fail together.

* `transactional = false` (on a sink, or `JooqSession(db, transactional = false)`) turns it off. Statements run
  straight on your context, so with a plain connection each is committed as it is written, and a failure leaves what
  was written so far. Use this for very large loads where one transaction would be too much for the database, or to
  take part in a transaction you manage yourself with `db.transaction { }`.
* Uncommitted rows are visible only to the sink's own connection. A source or `DbLookup` that reads through a
  different connection won't see them; one that shares the sink's connection (a context made from a single
  `Connection`) will.
* On MySQL the tables must be InnoDB. MyISAM ignores transactions, and so rolling back undoes nothing. Statements
  that change the schema (`CREATE TABLE`, etc.) commit implicitly on MySQL, so keep them out of a migration.

### Looking up values: `dbLookup`

```kotlin
take("country") {
    dbLookup(db, "countries", keyColumn = "name", valueColumn = "id")
    put("country_id")
}
```

Finds the first row whose `keyColumn` equals the value, and gives its `valueColumn` (or, with no `valueColumn`, the
whole row as a map, which you can `extract` from). A value that isn't found is handled like for `lookup`:
`passIfNotFound`, `onNotFound { }` and `warnIfNotFound` do the same. A row that is found but has null in the value
column gives null; it is not "not found".

Each lookup is a query. For repeated values, create a `DbLookup` once, outside the migration, with `cache = true`
(which also remembers misses, so don't use it on a table that the migration changes) and pass it in:

```kotlin
val countries = DbLookup(db, "countries", "name", "id", cache = true)

migrate(source, sink) {
    take("country") { dbLookup(countries); put("country_id") }
}
```

For a small table, loading it into a map once and using the ordinary `lookup` is simpler and faster:

```kotlin
val countries = db.selectFrom(DSL.table(DSL.name("countries"))).fetchMaps().associate { it["name"] to it["id"] }
```
