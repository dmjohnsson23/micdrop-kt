package micdrop

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SplitKvTest {
    @Test
    fun splitKvHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.splitKv(": "))
        assertNull(ctx.value)
    }

    @Test
    fun splitsAKnownGoodString() {
        val ctx = itemOf("A: 1\nB: 2\nC: 3")
        assertSame(ctx, ctx.splitKv(": "))
        check(mapOf("A" to "1", "B" to "2", "C" to "3"), ctx.value)
    }

    @Test
    fun splitsOnlyAtTheFirstKvDelimiter() {
        check(mapOf("url" to "http://x:80"), itemOf("url: http://x:80").splitKv(": ").value)
        check(mapOf("a" to "b=c"), itemOf("a=b=c").splitKv("=").value)
    }

    @Test
    fun customItemDelimiter() {
        check(mapOf("a" to "1", "b" to "2"), itemOf("a=1;b=2").splitKv("=", ";").value)
    }

    @Test
    fun ignoresBlankItems() {
        check(mapOf("a" to "1", "b" to "2"), itemOf("a=1\n\nb=2\n").splitKv("=").value)
    }

    @Test
    fun anItemWithoutADelimiterHasANullValue() {
        check(mapOf("a" to "1", "flag" to null), itemOf("a=1\nflag").splitKv("=").value)
    }

    @Test
    fun keepsTheOrderOfItems() {
        check(listOf("z", "a", "m"), (itemOf("z=1\na=2\nm=3").splitKv("=").value as Map<*, *>).keys.toList())
    }

    @Test
    fun canEnterTheResult() {
        val root = testRoot()
        itemOf("name: Ada\nrole: Dev", root).splitKv(": ") {
            take("name").put("n")
            take("role").put("r")
        }
        check(mapOf("n" to "Ada", "r" to "Dev"), root.collector)
    }
}

class JoinKvTest {
    @Test
    fun joinKvHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.joinKv(": "))
        assertNull(ctx.value)
    }

    @Test
    fun joinsAMap() {
        val ctx = itemOf(mapOf("A" to "1", "B" to "2"))
        assertSame(ctx, ctx.joinKv(": "))
        check("A: 1\nB: 2", ctx.value)
    }

    @Test
    fun customItemDelimiterAndNonStringValues() {
        check("a=1;b=", itemOf(mapOf("a" to 1, "b" to null)).joinKv("=", ";").value)
    }

    @Test
    fun roundTrips() {
        val text = "A: 1\nB: 2"
        check(text, itemOf(text).splitKv(": ").joinKv(": ").value)
    }

    @Test
    fun failsOnANonMap() {
        assertFailsWith<ValueError> { itemOf("x").joinKv(": ") }
    }
}

class JsonTest {
    @Test
    fun parseJsonHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.parseJson())
        assertNull(ctx.value)
    }

    @Test
    fun parsesObjectsIntoPlainMaps() {
        val ctx = itemOf("""{"a": 1, "b": "two", "c": [true, null, 2.5], "d": {"e": false}}""")
        assertSame(ctx, ctx.parseJson())
        check(
            mapOf("a" to 1L, "b" to "two", "c" to listOf(true, null, 2.5), "d" to mapOf("e" to false)),
            ctx.value,
        )
    }

    @Test
    fun keepsObjectKeyOrder() {
        val keys = (itemOf("""{"z":1,"a":2,"m":3}""").parseJson().value as Map<*, *>).keys.toList()
        check(listOf("z", "a", "m"), keys)
    }

    @Test
    fun parsesScalarsAndArrays() {
        check(listOf(1L, 2L), itemOf("[1, 2]").parseJson().value)
        check("text", itemOf("\"text\"").parseJson().value)
        check(5L, itemOf("5").parseJson().value)
        check(true, itemOf("true").parseJson().value)
        assertNull(itemOf("null").parseJson().value)
    }

    @Test
    fun stringsThatLookLikeNumbersStayStrings() {
        check(mapOf("zip" to "01234"), itemOf("""{"zip": "01234"}""").parseJson().value)
    }

    @Test
    fun entersANewSubContext() {
        val root = testRoot()
        itemOf("""{"a": 1, "b": 2}""", root).parseJson {
            check(1L, take("a").value)
            check(2L, take("b").value)
            take("a").put("A")
        }
        check(mapOf("A" to 1L), root.collector)
    }

    @Test
    fun theBlockCanTakeFromTopLevelArrays() {
        val root = testRoot()
        itemOf("""["x", "y"]""", root).parseJson { take(1).put("second") }
        check(mapOf("second" to "y"), root.collector)
    }

    @Test
    fun resultsWorkWithTheRestOfThePipeline() {
        val root = testRoot(mapOf("doc" to """{"person": {"names": ["Ada", "Lovelace"]}}"""))
        root.take("doc").parseJson().extractDig("person", "names", 1).put("last")
        check(mapOf("last" to "Lovelace"), root.collector)
    }

    @Test
    fun invalidJsonIsAValueError() {
        assertFailsWith<ValueError> { itemOf("{nope").parseJson() }
        assertFailsWith<ValueError> { itemOf("").parseJson() }
    }

    @Test
    fun invalidJsonCanBeRecoveredFrom() {
        assertNull(itemOf("{nope").pipeOrSetNull { parseJson() }.value)
    }

    @Test
    fun formatJsonHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.formatJson())
        assertNull(ctx.value)
    }

    @Test
    fun formatsCompactJson() {
        check("""["a","b"]""", itemOf(listOf("a", "b")).formatJson().value)
        check(
            """{"a":1,"b":[true,null,2.5],"c":{"d":"x"}}""",
            itemOf(mapOf("a" to 1, "b" to listOf(true, null, 2.5), "c" to mapOf("d" to "x"))).formatJson().value,
        )
    }

    @Test
    fun formatsScalars() {
        check("\"hi\"", itemOf("hi").formatJson().value)
        check("5", itemOf(5).formatJson().value)
        check("false", itemOf(false).formatJson().value)
    }

    @Test
    fun escapesStrings() {
        check("\"a\\\"b\\n\"", itemOf("a\"b\n").formatJson().value)
    }

    @Test
    fun writesOtherTypesAsStrings() {
        check("""{"d":"2024-11-14"}""", itemOf(mapOf("d" to LocalDate.of(2024, 11, 14))).formatJson().value)
    }

    @Test
    fun nonStringMapKeysBecomeStrings() {
        check("""{"1":"x"}""", itemOf(mapOf(1 to "x")).formatJson().value)
    }

    @Test
    fun roundTrips() {
        val json = """{"a":1,"b":["x",null,2.5],"c":{"d":true}}"""
        check(json, itemOf(json).parseJson().formatJson().value)
    }

    @Test
    fun collectListThenFormat() {
        val out = runMigration(listOf(mapOf("home" to null, "work" to "555", "cell" to "777"))) {
            collectList(take("home"), take("work"), take("cell")) { compact(); formatJson(); put("phones") }
        }
        check(listOf(mapOf("phones" to """["555","777"]""")), out)
    }
}
