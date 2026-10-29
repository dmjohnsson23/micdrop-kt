package micdrop.examples

import micdrop.ext.CsvSource
import micdrop.ext.jooq.InsertSink
import micdrop.migrate
import org.jooq.DSLContext
import java.nio.file.Path
import java.time.LocalDate

/**
 * Imports several CSV files into a normalized set of SQL tables, in several passes over the same files.
 *
 * Run with `./gradlew runCsvsToSqlExample`.
 */
object CsvsToSql {
    private val tables = listOf("users", "user_phones", "clients", "client_contacts", "client_addresses", "client_contact_phones")

    @JvmStatic
    fun main(args: Array<String>) = runExample(args, tables, ::run)

    fun run(db: DSLContext, dataDir: Path) {
        // Create the destination data structure.
        db.createTables(
            "users" to """
                id integer primary key autoincrement, username text, active boolean, pass_hash text, email text,
                f_name text, l_name text, sex text, dob date, occupation text, registration_date date,
                _tmp_id integer  -- A temporary column for storing the old system IDs
            """,
            "user_phones" to """
                id integer primary key autoincrement, user_id integer not null references users (id),
                type text, display text, search text
            """,
            "clients" to """
                id integer primary key autoincrement, name text, website text, registration_date date,
                _tmp_id integer
            """,
            "client_addresses" to """
                id integer primary key autoincrement, client_id integer not null references clients (id),
                line1 text, line2 text, city text, county text, state text, zip text, country text
            """,
            "client_contacts" to """
                id integer primary key autoincrement, client_id integer not null references clients (id),
                f_name text, l_name text, title text, email text, _tmp_id integer
            """,
            "client_contact_phones" to """
                id integer primary key autoincrement,
                client_contact_id integer not null references client_contacts (id),
                type text, display text, search text
            """,
        )

        // Now start the migration. Each migrate commits when it finishes, so each pass can see the earlier ones.

        // For the first pass, we'll import legacy users into the new system
        migrate(CsvSource.open(dataDir.resolve("people-100.csv")), InsertSink(db, "users")) {
            // Define mappings for common fields
            take("Index") { parseInt(); put("_tmp_id") }
            take("First Name").put("f_name")
            take("Last Name").put("l_name")
            take("Email").put("email")
            take("Job Title").put("occupation")
            // Use format strings in the pipeline
            take("User Id") {
                formatString("_legacy_user_%s")
                put("username")
            }
            // Use maps as lookup tables to convert values
            take("Sex") {
                lookup(mapOf("Male" to "m", "Female" to "f"))
                put("sex")
            }
            // Pipeline methods for common tasks such as parsing and formatting dates also exist
            take("Date of birth") {
                parseDate()
                put("dob")
            }
            // You can also put static or dynamically computed values in the sink for required items not found in
            // the source data.
            put("registration_date", LocalDate.now())
            put("active", false)
        }

        // Create a reusable lookup table for mapping the original source user IDs to the new destination user IDs
        // during subsequent migrations. (If this is expected to be too large for your memory constraints, you can
        // also query the new database directly during subsequent migrations with `dbLookup`, but for smaller
        // datasets using an in-memory map will usually be more performant.)
        val userLookup = db.selectHash("users", "_tmp_id", "id")

        // The new system uses a separate table for storing phone number data, so we make a second pass over the same
        // source to extract that
        migrate(CsvSource.open(dataDir.resolve("people-100.csv")), InsertSink(db, "user_phones")) {
            put("type", "Other")
            // We can use our lookup table to map the user IDs correctly
            take("Index") {
                parseInt()
                lookup(userLookup)
                put("user_id")
            }
            // We can skip rows that don't have a value in this column. (CSV reads an empty field as null.)
            take("Phone") {
                skipIfEmpty()
                put("display")
            }
            // Plain Kotlin can be used in pipelines for complex transformations. Note that `stringReplace` would be
            // a better way of doing the following; this example is merely to demonstrate what can be done if an
            // existing function does not already provide the needed functionality.
            take("Phone") {
                convert { (it as String).replace(Regex("[^0-9]"), "") }
                put("search")
            }
        }

        // We can import from another file in much the same way
        migrate(CsvSource.open(dataDir.resolve("customers-100.csv")), InsertSink(db, "clients")) {
            take("Index") { parseInt(); put("_tmp_id") }
            take("Company").put("name")
            take("Subscription Date") {
                parseDate()
                put("registration_date")
            }
            take("Website").put("website")
        }

        val clientLookup = db.selectHash("clients", "_tmp_id", "id")

        // A second pass with that file
        migrate(CsvSource.open(dataDir.resolve("customers-100.csv")), InsertSink(db, "client_contacts")) {
            take("Index") { parseInt(); put("_tmp_id") }
            take("Index") {
                parseInt()
                lookup(clientLookup)
                put("client_id")
            }
            take("First Name").put("f_name")
            take("Last Name").put("l_name")
            take("Email").put("email")
        }

        val clientContactLookup = db.selectHash("client_contacts", "_tmp_id", "id")

        // And a third for the addresses--we can take as many passes as we need to normalize our data
        migrate(CsvSource.open(dataDir.resolve("customers-100.csv")), InsertSink(db, "client_addresses")) {
            take("Index") {
                parseInt()
                lookup(clientLookup)
                put("client_id")
            }
            take("City").put("city")
            take("Country").put("country")
        }

        // This example shows a much more complex case.
        //
        // Sometimes, one record in the source data may need to turn into multiple records in the destination. You
        // can achieve this by flushing to the sink, which will create a destination record and reset, but maintain
        // the same source record.
        migrate(CsvSource.open(dataDir.resolve("customers-100.csv")), InsertSink(db, "client_contact_phones")) {
            // There is a "Phone 1" and "Phone 2" in the source, but the destination requires separate records for each.
            for (column in listOf("Phone 1", "Phone 2")) {
                // These values are the same for every iteration
                take("Index") {
                    parseInt()
                    lookup(clientContactLookup)
                    put("client_contact_id")
                }
                put("type", "Other")
                // But here we take a different column each time
                take(column) {
                    // Note that we can't use `skip` here, or both records would potentially be skipped. We have to
                    // use our own logic.
                    if (value == null) {
                        // Reset will clear the collector. So long as there are no puts between the reset and the
                        // ending flush, nothing will be inserted into the database.
                        reset()
                    } else {
                        put("display")
                        // We don't have to close a block after the first put; multiple puts are allowed if an item
                        // needs to be put in multiple places.
                        stringReplace(Regex("[^0-9]"), "")
                        put("search")
                    }
                }
                // Since we are inserting multiple records, it's important to flush after creating each. This creates
                // the record in the destination, and resets the collector for the next iteration.
                flush()
            }
        }
    }
}
