# Micdrop

A Kotlin library for migrating data from one place to another using a declarative pipeline style. It is primarily
intended for:

* Import scripts
* Export scripts
* Cross-application data migrations

Hence the name: "MIC" is an acronym for "Migrate, Import, Convert". The "drop" is just for fun.

At its core the library is simple: loop over the records of a source, run some transformations on their items, and
write the results to a sink.

```kotlin
val source = CsvSource.open(Path.of("people.csv"))
val sink = CsvSink.open(Path.of("people_clean.csv"))

// A migration defines how each record from the source becomes a record in the sink
migrate(source, sink) {
    take("Name").put("name")
    take("Birth Date") {
        parseDate("MM/dd/yy")
        formatDate("yyyy-MM-dd")
        put("dob")
    }
    take("Deceased?") {
        parseBoolean()
        default(false)
        put("is_deceased")
    }
}
```

> This is a Kotlin port of [micdrop-ruby](https://github.com/dmjohnsson23/micdrop-ruby), itself a re-implementation of
> a [Python original](https://github.com/dmjohnsson23/micdrop). It keeps the same concepts, reshaped around Kotlin
> idioms. See [docs/from-ruby.md](docs/from-ruby.md) if you are coming from the Ruby version.

## Documentation

* This README: a guided tour
* [docs/transforms.md](docs/transforms.md): reference for every function available in a migration
* [docs/sources-and-sinks.md](docs/sources-and-sinks.md): the built-in sources and sinks
* [docs/extending.md](docs/extending.md): writing your own transforms, sources, sinks and record types
* [docs/from-ruby.md](docs/from-ruby.md): differences from the Ruby version

The code is documented with KDoc as well.

## Setup

Micdrop is not published yet; build it from source with `./gradlew build`. It needs a JDK (the build is configured
for 24) and uses `kotlinx-serialization-json` and `commons-csv` internally. The code currently lives in the
placeholder package `org.example`, with optional pieces in `org.example.ext`:

```kotlin
import org.example.*
import org.example.ext.*        // CSV and JSON-Lines
import org.example.ext.jooq.*   // databases
import org.example.ext.jsoup.*  // HTML and XML
```

Database and HTML/XML support are optional dependencies: they are built against [jOOQ](https://www.jooq.org) and
[jsoup](https://jsoup.org), but don't bring them in. If you use one, add it (and for databases, a JDBC driver) to your
own project:

```kotlin
dependencies {
    implementation("org.jooq:jooq:3.21.9")
    runtimeOnly("...your JDBC driver...")
    implementation("org.jsoup:jsoup:1.23.2")
}
```

Everything else works without either on the classpath.

## Terminology

* **Source**: where data comes from; a sequence of Records.
* **Sink**: the final destination in which Records are stored after their transformations.
* **Record**: a single record in a Source (a CSV row, say). Anything with items you can look up by name or position.
* **Item**: a sub-component of a record (such as a column). Items can be Records themselves, if the data is
  structured, like JSON.
* **Take**: extract a single Item from a Record.
* **Put**: deposit a single Item into the Collector.
* **Collector**: the output record under construction. A fresh one exists for every source record, and is written to
  the Sink when it is flushed (by default, once at the end of each source record).

## Sources and sinks

Plain Kotlin collections work as sources and sinks, which is the easiest way to experiment:

```kotlin
val source = listOf(
    mapOf("a" to 1, "b" to 2),
    mapOf("a" to 3, "b" to 4),
)
val sink = mutableListOf<Collector>()   // Collector is a MutableMap<Any?, Any?>

migrate(source, sink) {
    take("a").put("A")
    take("b").put("B")
}

// sink is now: [{A=1, B=2}, {A=3, B=4}]
```

For real data there are sources for [CSV, JSON-Lines, XML, files and databases](docs/sources-and-sinks.md), and sinks
for CSV, JSON-Lines and databases. Writing your own is [straightforward](docs/extending.md).

## Simple migrations

`take` pulls one item out of the record and returns an `ItemContext` which you can transform and `put`. You can give
it a block, chain methods on the result, or both; these are equivalent:

```kotlin
migrate(source, sink) {
    take("a") {
        default(0)
        put("A")
    }
    take("a").default(0).put("A")
}
```

Each block is a pipeline: every transform runs in order and modifies the item's value in place. Pipelines can be as
long as you like, and may `put` at several stages.

```kotlin
val source = listOf(
    mapOf("a" to "Yes", "b" to "08/07/22", "c" to "Stuff"),
    mapOf("a" to "No", "b" to "11/12/21", "c" to null),
)

migrate(source, sink) {
    take("a") {
        parseBoolean()
        put("A")
    }
    take("b") {
        // Parse from a string, then format in the new format
        parseDate("MM/dd/yy")
        formatDate("yyyy-dd-MM")
        put("B")
    }
    take("c") {
        default("Whatsit")
        put("C")
    }
}

// sink is now: [{A=true, B=2022-07-08, C=Stuff}, {A=false, B=2021-12-11, C=Whatsit}]
```

Transforms leave `null` values alone (except those like `default` whose job is to handle null), so a column that is
sometimes empty doesn't need special handling. Date patterns are `java.time.format.DateTimeFormatter` patterns
(`MM/dd/yy`), not strftime. See the [transform reference](docs/transforms.md) for everything available.

If the source has a lot of columns that need no conversion and keep their names, use `passthru`:

```kotlin
migrate(source, sink) {
    passthru("a", "b")
}
```

### Taking items

* `take(name)` requires the item to exist, and fails with a `PipelineError` if it does not. (The value may still be
  null.)
* `tryTake(name)` gives null if the item does not exist.
* `takeDig(a, b, c)` takes from nested records.
* `takeWhole()` takes the whole record as one item.
* `static(value)` makes an item from a value you supply, for data the sink needs but the source lacks.
* `index()` takes the record's index (row number, map key, file name...).

```kotlin
migrate(source, sink) {
    index().put("row")
    static("Mailing").put("role")
}
```

## Destructuring data

If your source data is nested, use `scope` and `extract` to navigate the tree:

```kotlin
val source = listOf(
    mapOf("some" to mapOf("deeply" to mapOf("nested" to mapOf("data" to "1", "stuff" to "2")), "other" to "3")),
)

migrate(source, sink) {
    take("some") {
        scope {
            // `scope` stops operations in its block from affecting the value of the outer `take` block
            extract("other")
            put("some_other")
        }
        scope {
            // `extract` goes down the tree, and can be repeated
            extract("deeply")
            extract("nested")
            scope {
                extract("data")
                put("some_deeply_nested_data")
            }
        }
    }
    // For a single deeply-nested item, chain it
    take("some").extract("deeply").extract("nested").extract("stuff").put("stuff")
    // ... or use takeDig
    takeDig("some", "deeply", "nested", "stuff").put("stuff_again")
}
```

Lists can be sliced too: `extract(2..4)` keeps elements 2 through 4.

Anything can be entered as a record, making its contents takeable: maps, lists (by position), regex matches (by group
number or name), and anything else you register a [record adapter](docs/extending.md#record-types) for.
`parseJson`, `split`, `splitKv` and `regex` all take an optional block that does this with their result:

```kotlin
migrate(source, sink) {
    take("name").split(", ") {
        take(0).put("last_name")
        take(1).put("first_name")
    }
    take("details").parseJson {
        take("age").put("age")
    }
}
```

## Outputting structured data

By default Micdrop assumes your output is a flat row of columns, but it can build structure too.

`collectList` gathers several already-taken items into one list, which then goes through a pipeline:

```kotlin
val source = listOf(
    mapOf("person" to 1, "home_phone" to null, "work_phone" to "(354) 756-4796", "cell_phone" to "(234) 678-7564"),
)

migrate(source, sink) {
    take("person").put("person_id")
    collectList(take("home_phone"), take("work_phone"), take("cell_phone")) {
        // The value is a list of all three
        compact()
        formatJson()
        put("phones")
    }
}

// sink is now: [{person_id=1, phones=["(354) 756-4796","(234) 678-7564"]}]
```

There are other list helpers: `filter`, `map`, `coalesce` (the first non-null element), `join`, and `mapApply`, which
runs a full pipeline on each element.

`collectKv` does the same into a map, and `collectFormatString` into a string:

```kotlin
migrate(source, sink) {
    collectKv("Home" to take("home_phone"), "Work" to take("work_phone")) {
        formatJson()
        put("phones_by_kind")
    }
    collectFormatString("Home: %s, Work: %s", take("home_phone"), take("work_phone")) {
        put("contact")
    }
}
```

Finally, `putBury` builds nested structure as you go. It is `dig` in reverse:

```kotlin
migrate(source, sink) {
    take("person").put("person_id")
    take("home_phone") { if (value != null) putBury("phones", "home") }
    take("work_phone") { if (value != null) putBury("phones", "work") }
    take("cell_phone") { if (value != null) putBury("phones", "cell") }
}

// sink is now: [{person_id=1, phones={work=(354) 756-4796, cell=(234) 678-7564}}]
```

A key of `null` appends to a list, and an integer indexes into one (negative counts from the end):
`putBury("phones", null)` adds an element to the `phones` list.

## Creating multiple output records

A single source record can produce several sink records. The simplest way is `flush`:

```kotlin
val source = listOf(
    mapOf("person" to 1, "home_phone" to "(634) 654-2457", "work_phone" to "(354) 756-4796"),
)

migrate(source, sink) {
    take("person").put("person_id")
    take("home_phone").put("number")
    static("Home").put("type")
    flush() // Writes the first record and starts a new one
    take("person").put("person_id")
    take("work_phone").put("number")
    static("Work").put("type")
    // There is an implicit flush at the end, so we don't need one here
}

// sink is now: [{person_id=1, number=(634) 654-2457, type=Home}, {person_id=1, number=(354) 756-4796, type=Work}]
```

`flush(reset = false)` writes the record out but keeps its contents.

To iterate a list inside the source record, use `eachSubrecord`:

```kotlin
val source = listOf(
    mapOf("person" to 1, "addresses" to listOf(mapOf("city" to "Anytown", "role" to "Mailing"))),
    mapOf(
        "person" to 2,
        "addresses" to listOf(mapOf("city" to "Thereabouts", "role" to "Home"), mapOf("city" to "Elsewhere", "role" to "Mailing")),
    ),
)

migrate(source, sink) {
    // Keep this so we can put it again for every address
    val person = take("person")
    take("addresses").eachSubrecord(flush = true, reset = true) {
        person.put("person_id")
        take("city").put("city")
        take("role").put("role")
    }
}

// sink is now:
// [{person_id=1, city=Anytown, role=Mailing},
//  {person_id=2, city=Thereabouts, role=Home},
//  {person_id=2, city=Elsewhere, role=Mailing}]
```

### Multiple sinks

For records that belong in different sinks (say, different tables), the easiest approach is to migrate the same
source more than once, once per sink. If that is awkward or too slow, use a `MultiSink`, and `putBury` under the name
of the sink each value belongs to:

```kotlin
val people = mutableListOf<Collector>()
val addresses = mutableListOf<Collector>()
val sink = MultiSink("person" to people.asSink(), "address" to addresses.asSink())

migrate(source.asSource(), sink) {
    take("id") {
        putBury("person", "id")
        putBury("address", "person_id")
    }
    take("first_name").putBury("person", "fname")
    take("mail_city").putBury("address", "city")
    static("Mailing").putBury("address", "role")
}
```

A sub-sink whose part of the record stayed empty is skipped.

## Skipping and stopping

`skip()` abandons the current source record (like `continue`), and `stop()` ends the migration (like `break`). They
work anywhere in a migration, including inside a `take` block or a reusable pipeline. There are also shortcuts that
act on an item's value: `skipIf`, `skipIfEmpty`, `stopIf` and `stopIfEmpty`.

```kotlin
migrate(source, sink) {
    take("status") {
        skipIf("deleted")
        stopIf("END")
        put("status")
    }
}
```

Anything put so far in a skipped record is discarded.

## Reusable pipelines

A pipeline is just a lambda with an `ItemContext` receiver, so a repeated one can be stored in a variable and
applied:

```kotlin
val defaultZero: Pipeline = { default(0) }

migrate(source, sink) {
    take("a").pipe(defaultZero).put("A")
    take("b") {
        pipe(defaultZero)
        put("B")
    }
}
```

## Filling the gaps with plain Kotlin

Where the built-in transforms don't cover you, drop into Kotlin with `convert` or `update`:

```kotlin
migrate(source, sink) {
    take("a").convert { (it as Int) + 1 }.put("A")
    take("c") {
        update(if ((value as Int) % 2 == 0) "Even" else "Odd")
        put("C")
    }
}
```

Values are untyped (`Any?`) because a pipeline freely changes types as it goes, from text to number to date and back,
so you cast where you use plain Kotlin. If you write the same code often, [add your own
transform](docs/extending.md#transforms) as an extension function so it chains like the built-in ones.

## Lookups

`lookup` converts values through a map:

```kotlin
migrate(source, sink) {
    take("sex") {
        pipeOrRevert { lookup(mapOf("M" to "Male", "F" to "Female")) }
        put("sex")
    }
}
```

An unknown value throws a `PipelineError`. Wrap the lookup in `pipeOrRevert { }` to keep the original value instead,
`pipeOrSetNull { }` to make it null, or `pipeOrFallback({ lookup(...) }, { ... })` to run another pipeline on it. For lookups used in many places, register them once and
refer to them by name:

```kotlin
ItemContext.registerLookup("sex", mapOf("M" to "Male", "F" to "Female"))

migrate(source, sink) {
    take("sex").lookup("sex").put("sex")
}
```

## Handling bad data

Transforms throw on values they can't handle (`ValueError`, or the usual JDK exceptions like `NumberFormatException`
and `DateTimeParseException`), which stops the migration. When the data is expected to be messy, use the `pipeOr...`
family to decide what happens instead:

```kotlin
migrate(source, sink) {
    // null if it can't be parsed
    take("age").pipeOrSetNull { parseInt() }.put("age")
    // keep the original text if it can't be parsed
    take("born").pipeOrRevert { parseDate("MM/dd/yyyy") }.put("born")
    // try something else
    take("zip").pipeOrFallback({ parseInt() }, { nullIf("N/A") }).put("zip")
}
```

`skip()` and `stop()` are never swallowed by these.

## Hooks

`beforeFlush` and `afterFlush` run around every write to the sink. They receive the root context and the collector,
for example to log what happened or look up an ID the sink generated:

```kotlin
migrate(source, sink) {
    afterFlush { root, collected ->
        println("Wrote record ${root.loopIndex}: $collected")
    }
    // Then your normal migration operations
}
```

Register hooks before the first flush, or they will miss it.

## HTML and XML

With [jsoup](https://jsoup.org) on the classpath (see [Setup](#setup)), HTML and XML elements are records, and you
find things in them with CSS selectors or XPath.

### XML files

`XmlSource` streams an XML file, with one record per element you choose, so it works however big the file is. Only one
record is in memory at a time.

```kotlin
// One record per <product> inside <catalog>
migrate(XmlSource.open(Path.of("catalog.xml"), "catalog/product"), sink) {
    take("id").put("code")                           // attributes are the items of an element
    atCss("name").takeContent().put("name")          // find a child, take its text
    atCss("price") {
        take("currency").put("currency")
        takeContent().parseDouble().put("price")
    }
}
```

The element is given as a path of names: `"product"` matches any `product`, `"catalog/product"` those directly inside
a `catalog`, and `"/catalog/product"` only exactly there from the root. It also reads strictly, so a damaged file fails the
migration with the line number instead of quietly yielding some of its records.

Each record can have lists inside it. `css` and `xpath` give all the matches, and `eachSubrecord` goes over them to
make several output records:

```kotlin
migrate(XmlSource.open(Path.of("catalog.xml"), "product"), sink) {
    val code = take("id")
    css("specifications > *").eachSubrecord(flush = true, reset = true) {
        code.put("code")
        takeNodeName().put("spec")        // the element's name, e.g. "ram"
        takeContent().put("value")
    }
}
```

### XML and HTML in a value

When the markup is in a value, say a database column or a JSON string, parse it in the pipeline. The optional block
works like it does for `parseJson`:

```kotlin
migrate(source, sink) {
    take("notes_html").parseHtml {
        atCss(".summary").takeContent().put("summary")
        atCss("a").take("href").put("link")
    }
    take("order_xml").parseXml {
        css("item").eachSubrecord(flush = true, reset = true) {
            take("sku").put("sku")
            takeContent().parseInt().put("quantity")
        }
    }
}
```

* `parseXml` checks that the XML is well-formed and fails with a `ValueError` if it is not, because jsoup on its own
  accepts broken XML without a word. `parseXml(strict = false)` turns the check off.
* `parseHtml` reads HTML the way a browser does, so even broken HTML works. `parseHtmlFragment` is for a piece of
  HTML that isn't a whole page.
* `parseXmlFragment` is for XML with more than one top-level element.
* `takeContent()` is the text of an element and everything in it, exactly as written; add `.strip()` to trim it.
  `takeNodeName()` is the element's name.
* `css`, `atCss`, `xpath` and `atXpath` search inside an element (the `at` versions give just the first match, or null).
* For text in the middle of a value, `decodeHtml()`, `encodeHtml()`, `decodeXml()` and `encodeXml()` convert
  between plain text and entities like `&lt;`.

See [HTML and XML in the sources and sinks guide](docs/sources-and-sinks.md#html-and-xml) for the details.

## Databases

With [jOOQ](https://www.jooq.org) on the classpath (see [Setup](#setup)), a database can be a source or a sink. You
make a jOOQ `DSLContext` for your database, and tables are named by strings, so there is nothing to declare:

```kotlin
val db = DSL.using(dataSource, SQLDialect.MYSQL)

// Any jOOQ query is a source, with the jOOQ DSL for filtering, joining and ordering
val alive = db.selectFrom(DSL.table(DSL.name("people"))).where(DSL.field(DSL.name("status")).eq("alive"))

migrate(JooqSource(alive), InsertSink(db, "people_archive")) {
    take("first_name").put("first_name")
    take("last_name").put("last_name")
}
```

Rows are maps keyed by column name, dates come out as `java.time` types, and the sinks take a record's keys as the
columns to write. There are three sinks:

* `InsertSink(db, "table")` inserts every record as a new row.
* `UpdateSink(db, "table", keys = listOf("id"))` updates the row each record's keys identify.
* `InsertUpdateSink(db, "table", keys = listOf("id"))` updates the row if there is one, or inserts it if not. Which
  value wins when the row exists is chosen per column:

```kotlin
val sink = InsertUpdateSink(
    db, "people", keys = listOf("email"),
    updateActions = mapOf(
        "notes" to UpdateAction.AppendLine,   // keep the old notes and add the new
        "visits" to UpdateAction.Add,         // add to the old count
        "created" to UpdateAction.KeepExisting,
    ),
    // The default is Coalesce: take the new value, unless it is null
)
```

To find out which ID the database gave a new row, read `lastInsertId` of the sink in an `afterFlush` hook:

```kotlin
val sink = InsertSink(db, "clients")
migrate(source, sink) {
    take("Name").put("name")
    afterFlush { _, _ -> println("Created client ${sink.lastInsertId}") }
}
```

And `dbLookup` converts values using a table, such as turning a name into the ID of the row that has it:

```kotlin
migrate(source, sink) {
    take("country_name") {
        dbLookup(db, "countries", keyColumn = "name", valueColumn = "id")
        put("country_id")
    }
}
```

That is a query per item. If values repeat a lot, make the lookup once outside the migration with caching:
`val countries = DbLookup(db, "countries", "name", "id", cache = true)`, then `dbLookup(countries)`. For a small
table, you can instead load it into a map and use the ordinary `lookup`.

See [the database section of the sources and sinks guide](docs/sources-and-sinks.md#databases) for the details,
including what to do for large tables on MySQL.

## Transactions

Sinks which can undo their writes, like the database sinks, do so by default: nothing is made permanent until
the migration finishes successfully, and if it fails, **everything written is rolled back**. Ending early with
`stop()` counts as success.

```kotlin
// Either all of these rows are inserted, or none are
migrate(source, InsertSink(db, "people")) {
    take("name").put("name")
    take("age").parseInt().put("age")   // a bad age fails the migration and undoes the earlier rows
}
```

This is built into `migrate`, which commits a sink when it is done and rolls it back when anything goes wrong
(see [extending](docs/extending.md#sinks) to support it in your own sinks). A few things to know about it:

* **Several tables at once:** sinks made separately from a `DSLContext` each have their own transaction. For writes
  to different tables that must succeed or fail together, give the sinks one `JooqSession`:

  ```kotlin
  val session = JooqSession(db)
  val sink = MultiSink("person" to InsertSink(session, "people"), "phone" to InsertSink(session, "phones"))
  ```

* **Huge loads:** one very large transaction can be a problem for the database (MySQL's undo log, say). Pass
  `transactional = false` to a sink to have each row committed as it is written, as if there were no transactions.
  The price is that a failure leaves the rows written so far.
* **Your own transactions:** `transactional = false` also lets a sink take part in a transaction you manage with
  jOOQ's `db.transaction { ... }`.
* **MySQL tables must be InnoDB** for any of this to work. MyISAM tables ignore transactions.
* Other connections can't see the rows until the commit. That includes lookups and sources reading through a
  different connection.

## Debugging

`dump()` prints the current value to the console and returns the item, so it can be dropped anywhere in a chain.
`dumpCollector()` prints the output record built so far.

```kotlin
take("a").dump("before").parseInt().dump("after").put("A")
```

## Command-line runner

The `cli` project bundles micdrop, jOOQ, jsoup, SQLite's JDBC driver and the Kotlin compiler into a standalone
application. Build it with `./gradlew :cli:installDist`, which creates `cli/build/install/micdrop/` (put its `bin`
directory on your `PATH`; it needs Java 24 or later), and run a script with:

    micdrop my_script.micdrop.kts [args...]

Scripts need no `package` or `import` lines for micdrop (`org.example` and its `ext` packages are imported
automatically), and have the command-line arguments in `args`. Further libraries come from Maven with
`@file:DependsOn("group:artifact:version")` (and `@file:Repository("https://...")` for repositories other than Maven
Central). A script that fails exits with a nonzero status.

### A self-contained bundle

To run it on machines without Java, `./gradlew :cli:appImageTar` uses `jpackage` to create
`cli/build/distributions/micdrop-bundle.tar.gz`: a native launcher, a private Java runtime and the jars, about 170 MB.
Unpack it anywhere (say `/opt`) and link `micdrop/bin/micdrop` into your `PATH`; nothing else needs installing. (On
Windows the launcher is `micdrop.exe` in the top directory.)

`jpackage` only builds for the operating system it runs on. To make the Linux bundle from another system, run
`docker/build-linux-bundle.sh` (it needs Docker). Set `BASE_IMAGE` to a distribution no newer than your servers', since
a binary built against a newer glibc may not start on an older one.

## Examples

The `examples` directory has three complete migrations, ported from the Ruby version, with sample data in
`examples/data`. Each writes to a SQLite database, `examples/test.db`:

* `CsvsToSql`: several CSV files normalized into related tables over several passes, using lookups to carry the new IDs
  forward, and flushing to turn one row into two records. `./gradlew runCsvsToSqlExample`
* `JsonFilesToSql`: a directory of JSON files, with names split and street addresses pulled apart by a regular
  expression. `./gradlew runJsonFilesToSqlExample`
* `XmlToSql`: an XML catalog streamed into a table of products and a table of their specifications.
  `./gradlew runXmlToSqlExample`

They are separate from the library (a Gradle source set of their own), and are run by the tests, which check what they
write.

## Tests

`./gradlew test`. The examples in this README are checked by `ReadmeExamplesTest` and its siblings, and the programs
in `examples` by `ExamplesTest`.
