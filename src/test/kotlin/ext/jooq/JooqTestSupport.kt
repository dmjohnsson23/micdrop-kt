package org.example.ext.jooq

import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

fun table(name: String) = DSL.table(DSL.name(name))
fun column(vararg name: String) = DSL.field(DSL.name(*name))

/** All rows of a table as maps, in the order of [orderBy] */
fun DSLContext.rows(table: String, orderBy: String? = null): List<Map<String, Any?>> =
    selectFrom(table(table)).let { if (orderBy != null) it.orderBy(column(orderBy)) else it }.fetchMaps()

fun DSLContext.count(table: String) = fetchCount(table(table))

/** The People, Asset Type and Inventory tables from the Ruby tests, in an in-memory SQLite database */
abstract class InMemoryDatabaseTest {
    lateinit var connection: Connection
    lateinit var db: DSLContext

    @BeforeTest
    fun createDatabase() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        db = DSL.using(connection, SQLDialect.SQLITE)
        createSchema(db)
    }

    @AfterTest
    fun closeDatabase() {
        connection.close()
    }
}

fun createSchema(db: DSLContext) {
    db.execute(
        """create table people (
            id integer primary key, first_name text, last_name text, title text, status text, notes text, visits integer
        )"""
    )
    db.execute("insert into people (id, title, first_name, last_name, status) values (1, 'Farm Boy', 'Wesley', '', 'alive')")
    db.execute("insert into people (id, title, first_name, last_name, status) values (2, '', 'Inigo', 'Montoya', 'alive')")
    db.execute("insert into people (id, title, first_name, last_name, status) values (3, '', 'Buttercup', '', 'alive')")

    db.execute("create table asset_type (id integer primary key, label text)")
    db.execute("insert into asset_type values (1, 'ROUS'), (2, 'Wheelbarrow'), (3, 'Iocane Powder'), (4, 'True Love')")

    db.execute("create table inventory (person integer, asset_type integer, count integer)")
    db.execute("insert into inventory values (1, 4, 1), (1, 3, 1), (2, 2, 1), (3, 4, 0)")
}
