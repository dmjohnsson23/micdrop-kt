package org.example.examples

import org.example.ext.jooq.InsertSink
import org.example.ext.jsoup.XmlSource
import org.example.ext.jsoup.atCss
import org.example.ext.jsoup.css
import org.example.ext.jsoup.takeContent
import org.example.ext.jsoup.takeNodeName
import org.example.migrate
import org.jooq.DSLContext
import java.nio.file.Path

/**
 * Imports the products of an XML catalog, and then their specifications, which become rows of their own.
 *
 * Run with `./gradlew runXmlToSqlExample`.
 */
object XmlToSql {
    @JvmStatic
    fun main(args: Array<String>) = runExample(args, listOf("products", "product_specs"), ::run)

    fun run(db: DSLContext, dataDir: Path) {
        // Create the destination data structure.
        db.createTables(
            "products" to "code text primary key, name text, category text, price decimal(6, 2), stock integer",
            "product_specs" to "code text, \"key\" text, value text, primary key (code, \"key\")",
        )

        // Now start the migration
        val catalog = dataDir.resolve("catalog.xml")

        // Our source will go over the <product> elements in the XML document, one at a time. (This streams the file,
        // so it would work however large the catalog was.)
        migrate(XmlSource.open(catalog, "catalog/product"), InsertSink(db, "products")) {
            // The attributes of an element are its takeable items
            take("id").put("code")
            // Child elements are found with selectors, and their text taken
            atCss("name").takeContent().put("name")
            atCss("category").takeContent().put("category")
            atCss("price").takeContent {
                parseDouble()
                put("price")
            }
            atCss("quantity").takeContent().parseInt().put("stock")
        }

        // Then over the individual specs
        val specNames = mapOf(
            "battery" to "Battery",
            "buttons" to "Button Count",
            "connectivity" to "Connectivity",
            "display" to "Screen",
            "dpi" to "Screen DPI",
            "graphics" to "GPU",
            "processor" to "CPU",
            "ram" to "Memory",
            "storage" to "Storage",
        )

        migrate(XmlSource.open(catalog, "catalog/product"), InsertSink(db, "product_specs")) {
            val code = take("id")
            // Each element inside <specifications> becomes a record of its own
            css("specifications > *").eachSubrecord(flush = true, reset = true) {
                code.put("code")
                takeNodeName {
                    lookup(specNames)
                    put("key")
                }
                takeContent().put("value")
            }
        }
    }
}
