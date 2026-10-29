package micdrop.examples

import micdrop.FilesSource
import micdrop.ext.jooq.InsertSink
import micdrop.migrate
import micdrop.parseJson
import org.jooq.DSLContext
import java.nio.file.Path

/**
 * Imports a directory of JSON files, one person per file, splitting names and street addresses out of the text.
 *
 * Run with `./gradlew runJsonFilesToSqlExample`.
 */
object JsonFilesToSql {
    @JvmStatic
    fun main(args: Array<String>) = runExample(args, listOf("people"), ::run)

    fun run(db: DSLContext, dataDir: Path) {
        // Create the destination data structure.
        db.createTables(
            "people" to """
                id integer primary key autoincrement, f_name text, l_name text, addr1 text, addr2 text, city text,
                state text, zip text,
                _tmp_id text  -- A temporary column for storing the old system IDs
            """,
        )

        // Now start the migration

        // Our source will iterate over all the JSON files in the given directory
        val source = FilesSource(dataDir, glob = "json/*.json")
        val sink = InsertSink(db, "people")

        val address = Regex(
            "(?<street>.+?) ?(?<unit>(?:Apt\\.?|Suite|Unit|#) [#0-9a-zA-Z-]+)?\\n" +
                "(?<city>.+?), (?<state>[A-Z]{2}) (?<zip>\\d{5}(?:-\\d{4})?)"
        )

        migrate(source, sink) {
            // The files source exposes the basename and content as takeable items
            take("basename").put("_tmp_id")
            take("content").parseJson {
                // parseJson accepts a block that will enter the sub-record
                take("residency") {
                    // A file whose address doesn't match the pattern (9.json has a stray backslash in it) gets a
                    // warning, and the address is left out; the rest of the person is still imported.
                    regex(address) {
                        // regex also enters a sub-record, where groups are taken by name
                        take("street").put("addr1")
                        take("unit").put("addr2")
                        take("city").put("city")
                        take("state").put("state")
                        take("zip").put("zip")
                    }
                }
                take("name") {
                    split(" ") {
                        // split can enter a sub-record as well
                        take(0).put("f_name")
                        take(1).put("l_name")
                    }
                }
            }
        }
    }
}
