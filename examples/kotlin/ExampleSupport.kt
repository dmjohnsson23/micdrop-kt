package org.example.examples

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.nio.file.Path
import java.sql.DriverManager

/**
 * Runs [example] against a SQLite database file (the first argument, or `test.db`) with the sample data in `data`.
 * The examples are meant to be run from the `examples` directory, as the Gradle tasks do.
 */
internal fun runExample(args: Array<String>, tables: List<String>, example: (DSLContext, Path) -> Unit) {
    val file = args.getOrNull(0) ?: "test.db"
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        val db = DSL.using(connection, SQLDialect.SQLITE)
        example(db, Path.of("data"))
        println("Wrote $file:")
        for (table in tables) println("  ${db.fetchCount(DSL.table(DSL.name(table)))} rows in $table")
    }
}

/**
 * Create (afresh) the tables a migration will fill. Obviously in a real import script, these would probably already
 * exist. The tables are named in the order they should be created, so that those which others refer to come first.
 */
internal fun DSLContext.createTables(vararg definitions: Pair<String, String>) {
    for ((name, _) in definitions.reversed()) execute("drop table if exists \"$name\"")
    for ((name, columns) in definitions) execute("create table \"$name\" ($columns)")
}

/**
 * The equivalent of Sequel's `select_hash`: a map from one column of a table to another. Used to translate the IDs
 * of the old system into the IDs the new one gave the rows.
 */
internal fun DSLContext.selectHash(table: String, key: String, value: String): Map<Any?, Any?> =
    select(DSL.field(DSL.name(key)), DSL.field(DSL.name(value)))
        .from(DSL.table(DSL.name(table)))
        .fetch()
        .associate { it.value1() to it.value2() }
