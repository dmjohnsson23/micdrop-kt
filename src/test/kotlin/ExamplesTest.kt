package org.example

import org.example.examples.CsvsToSql
import org.example.examples.JsonFilesToSql
import org.example.examples.XmlToSql
import org.example.ext.jooq.InMemoryDatabaseTest
import org.example.ext.jooq.column
import org.example.ext.jooq.count
import org.example.ext.jooq.normalize
import org.example.ext.jooq.rows
import org.example.ext.jooq.table
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the examples in `examples/kotlin` on their sample data, in an in-memory database, and checks what they wrote */
class ExamplesTest : InMemoryDatabaseTest() {
    private val data = Path.of("examples/data")

    private fun rows(table: String, orderBy: String = "id") = db.rows(table, orderBy)

    // region CSV files

    @Test
    fun csvsToSqlWritesEveryTable() {
        CsvsToSql.run(db, data)
        for (table in listOf("users", "user_phones", "clients", "client_contacts", "client_addresses")) {
            check(100, db.count(table))
        }
        check(200, db.count("client_contact_phones")) // two phone numbers for each of 100 customers
    }

    @Test
    fun csvsToSqlConvertsUsers() {
        CsvsToSql.run(db, data)
        val user = rows("users").first()
        check(1, user["_tmp_id"])
        check("_legacy_user_88F7B33d2bcf9f5", user["username"])
        check("Shelby", user["f_name"])
        check("Terrell", user["l_name"])
        check("m", user["sex"])
        check("elijah57@example.net", user["email"])
        check("Games developer", user["occupation"])
        check(LocalDate.of(1945, 10, 26), normalize(user["dob"]))
        check(LocalDate.now(), normalize(user["registration_date"]))
        check(false, user["active"])
        check(setOf("m", "f"), rows("users").map { it["sex"] }.toSet())
    }

    @Test
    fun csvsToSqlLinksPhonesToTheNewUserIds() {
        CsvsToSql.run(db, data)
        val users = rows("users").associateBy { it["id"] }
        val phones = rows("user_phones")
        check(users.keys, phones.map { it["user_id"] }.toSet())
        // The phones are linked through the old system's index to the right user
        val first = phones.first()
        check(1, users.getValue(first["user_id"])["_tmp_id"])
        check("Other", first["type"])
        check("001-084-906-7849x73518", first["display"])
        check("001084906784973518", first["search"])
    }

    @Test
    fun csvsToSqlLinksTheClientTables() {
        CsvsToSql.run(db, data)
        val clients = rows("clients").associateBy { it["id"] }
        val client = clients.values.first()
        check("Rasmussen Group", client["name"])
        check(LocalDate.of(2020, 8, 24), normalize(client["registration_date"]))
        check("http://www.stephenson.com/", client["website"])

        val contacts = rows("client_contacts")
        check(clients.keys, contacts.map { it["client_id"] }.toSet())
        check("Sheryl", contacts.first()["f_name"])
        check("Baxter", contacts.first()["l_name"])

        val address = rows("client_addresses").first()
        check("East Leonard", address["city"])
        check("Chile", address["country"])
        check(client["id"], address["client_id"])
    }

    @Test
    fun csvsToSqlMakesARecordForEachPhoneOfAContact() {
        CsvsToSql.run(db, data)
        val contacts = rows("client_contacts").associateBy { it["id"] }
        val phones = rows("client_contact_phones")
        check(contacts.keys, phones.map { it["client_contact_id"] }.toSet())
        // The first customer has two phones: one record each, in order
        val first = phones.take(2)
        check(listOf("229.077.5154", "397.884.0519x718"), first.map { it["display"] })
        check(listOf("2290775154", "3978840519718"), first.map { it["search"] })
        check(1, first.map { it["client_contact_id"] }.toSet().size)
        check(100, phones.groupBy { it["client_contact_id"] }.size)
        assertTrue(phones.all { it["type"] == "Other" })
    }

    @Test
    fun csvsToSqlCanBeRunAgain() {
        CsvsToSql.run(db, data)
        CsvsToSql.run(db, data)
        check(100, db.count("users"))
        check(200, db.count("client_contact_phones"))
    }

    // endregion

    // region JSON files

    @Test
    fun jsonFilesToSqlWritesOnePersonPerFile() {
        JsonFilesToSql.run(db, data)
        check(10, db.count("people"))
        check(
            listOf("1.json", "2.json", "3.json", "4.json", "5.json", "6.json", "7.json", "8.json", "9.json", "a.json"),
            rows("people").map { it["_tmp_id"] },
        )
    }

    @Test
    fun jsonFilesToSqlSplitsNamesAndAddresses() {
        JsonFilesToSql.run(db, data)
        val brenda = rows("people").first { it["_tmp_id"] == "1.json" }
        check("Brenda", brenda["f_name"])
        check("Smith", brenda["l_name"])
        check("50156 Dorothy Springs", brenda["addr1"])
        check("Apt. 068", brenda["addr2"])
        check("Robertfurt", brenda["city"])
        check("WV", brenda["state"])
        check("95071", brenda["zip"])
    }

    @Test
    fun jsonFilesToSqlReadsTheUnitAndKeepsZipsAsText() {
        JsonFilesToSql.run(db, data)
        val person = rows("people").first { it["_tmp_id"] == "a.json" }
        check("Lauren", person["f_name"])
        check("566 Cameron Ranch", person["addr1"])
        check("Suite 297", person["addr2"])
        check("IA", person["state"])
        check("07395", person["zip"]) // a string, so the leading zero survives
    }

    @Test
    fun jsonFilesToSqlStillImportsAPersonWhoseAddressDoesNotMatch() {
        JsonFilesToSql.run(db, data)
        val brandon = rows("people").first { it["_tmp_id"] == "9.json" }
        check("Brandon", brandon["f_name"])
        check("Brown", brandon["l_name"])
        assertNull(brandon["addr1"])
        assertNull(brandon["addr2"])
        assertNull(brandon["city"])
        assertNull(brandon["zip"])
    }

    @Test
    fun jsonFilesToSqlSplitsTitlesAsNamesToo() {
        // As the Ruby example does: a name with a title, "Dr. Todd Sanchez", is split on its first two words
        JsonFilesToSql.run(db, data)
        val todd = rows("people").first { it["_tmp_id"] == "3.json" }
        check("Dr.", todd["f_name"])
        check("Todd", todd["l_name"])
    }

    // endregion

    // region XML

    @Test
    fun xmlToSqlWritesProductsAndSpecs() {
        XmlToSql.run(db, data)
        check(2, db.count("products"))
        check(9, db.count("product_specs"))
    }

    @Test
    fun xmlToSqlConvertsProducts() {
        XmlToSql.run(db, data)
        val products = rows("products", "code")
        check(listOf("P001", "P002"), products.map { it["code"] })
        check("Laptop Pro 15", products[0]["name"])
        check("Electronics", products[0]["category"])
        check(1299.99, (products[0]["price"] as Number).toDouble())
        check(45, products[0]["stock"])
        check("Wireless Mouse", products[1]["name"])
        check(230, products[1]["stock"])
    }

    @Test
    fun xmlToSqlRenamesSpecs() {
        XmlToSql.run(db, data)
        val specs = db.selectFrom(table("product_specs")).orderBy(column("code"), column("key")).fetchMaps()
            .map { Triple(it["code"], it["key"], it["value"]) }
        check(
            listOf(
                Triple("P001", "CPU", "Intel Core i7-13700H"),
                Triple("P001", "GPU", "NVIDIA RTX 4060"),
                Triple("P001", "Memory", "16GB DDR5"),
                Triple("P001", "Screen", "15.6\" FHD IPS"),
                Triple("P001", "Storage", "512GB NVMe SSD"),
                Triple("P002", "Battery", "2x AA"),
                Triple("P002", "Button Count", "6"),
                Triple("P002", "Connectivity", "Bluetooth 5.0"),
                Triple("P002", "Screen DPI", "1600"),
            ),
            specs,
        )
    }

    // endregion

    @Test
    fun theExamplesCanShareADatabase() {
        CsvsToSql.run(db, data)
        JsonFilesToSql.run(db, data)
        XmlToSql.run(db, data)
        check(100, db.count("users"))
        check(10, db.count("people"))
        check(2, db.count("products"))
    }
}
