package org.example

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ItemContextTest {
    // region value / convert / pipe

    @Test
    fun valueIsReadableAndSettable() {
        val ctx = itemOf(10)
        check(10, ctx.value)
        check(10, ctx.originalValue)
        ctx.value = 20
        check(20, ctx.value)
        check(10, ctx.originalValue)
    }

    @Test
    fun updateReplacesValue() {
        val ctx = itemOf(10)
        assertSame(ctx, ctx.update(20))
        check(20, ctx.value)
    }

    @Test
    fun convertRunsPlainKotlin() {
        val ctx = itemOf(10)
        assertSame(ctx, ctx.convert { (it as Int) * 2 })
        check(20, ctx.value)
    }

    @Test
    fun convertSeesNull() {
        check("was null", itemOf(null).convert { if (it == null) "was null" else it }.value)
    }

    @Test
    fun applyRunsAPipeline() {
        val ctx = itemOf("10")
        assertSame(ctx, ctx.pipe { parseInt() })
        check(10, ctx.value)
    }

    @Test
    fun applyAcceptsAReusablePipeline() {
        val plusOne: Pipeline = { convert { (it as Int) + 1 } }
        check(3, itemOf(1).pipe(plusOne).pipe(plusOne).value)
    }

    @Test
    fun pipeOrSetNull() {
        check(10, itemOf("10").pipeOrSetNull { parseInt() }.value)
        assertNull(itemOf("ten").pipeOrSetNull { parseInt() }.value)
    }

    @Test
    fun pipeOrRevertRestoresThePreviousValue() {
        val ctx = itemOf("ten").pipeOrRevert { strip(); parseInt() }
        check("ten", ctx.value)
    }

    @Test
    fun pipeOrFallback() {
        val ctx = itemOf("ten").pipeOrFallback({ parseInt() }, { update(0) })
        check(0, ctx.value)
        check(5, itemOf("5").pipeOrFallback({ parseInt() }, { update(0) }).value)
    }

    @Test
    fun pipeOrFamilyWarnsOnStderrOnlyWhenAsked() {
        fun stderrOf(block: () -> Unit): String {
            val original = System.err
            val captured = java.io.ByteArrayOutputStream()
            System.setErr(java.io.PrintStream(captured))
            try {
                block()
            } finally {
                System.setErr(original)
            }
            return captured.toString()
        }
        check("", stderrOf { itemOf("ten").pipeOrSetNull { parseInt() } })
        check("", stderrOf { itemOf("ten").pipeOrRevert { parseInt() } })
        check("", stderrOf { itemOf("ten").pipeOrFallback({ parseInt() }, { update(0) }) })
        val setNull = stderrOf { itemOf("ten").pipeOrSetNull(warn = "Bad number") { parseInt() } }
        val revert = stderrOf { itemOf("ten").pipeOrRevert(warn = "Bad number") { parseInt() } }
        val fallback = stderrOf { itemOf("ten").pipeOrFallback({ parseInt() }, { update(0) }, warn = "Bad number") }
        for (out in listOf(setNull, revert, fallback)) assertTrue(out.startsWith("Bad number: "), out)
        check("", stderrOf { itemOf("10").pipeOrSetNull(warn = "Bad number") { parseInt() } })
    }

    // endregion

    // region enter / scope / extract

    @Test
    fun enterReturnsANewSubRecordContext() {
        val value = mapOf("a" to 1, "b" to 2)
        val ctx = itemOf(value)
        val entered = ctx.enter()
        assertNotSame<Any>(ctx, entered)
        assertSame(value, entered.record?.raw)
    }

    @Test
    fun enterCallsEachHaveTheirOwnScope() {
        val ctx = itemOf(mapOf("a" to 1, "b" to 2))
        val s1 = ctx.enter().take("a")
        val s2 = ctx.enter().take("b")
        val s3 = ctx.enter().take("b").update(3)
        check(1, s1.value)
        check(2, s2.value)
        check(3, s3.value)
    }

    @Test
    fun enterStillPutsToTheMainScope() {
        val root = testRoot()
        val ctx = itemOf(mapOf("a" to 1, "b" to 2), root)
        ctx.enter {
            take("a").put("a")
            take("b").put("b")
        }
        check(mapOf("a" to 1, "b" to 2), root.collector)
    }

    @Test
    fun enterOnANonRecordFails() {
        assertFailsWith<PipelineError> { itemOf(5).enter() }
    }

    @Test
    fun scopeContainsUpdates() {
        val ctx = itemOf(10)
        val scoped = ctx.scope()
        assertNotSame(ctx, scoped)
        ctx.update(9)
        scoped.update(11)
        check(9, ctx.value)
        check(11, scoped.value)
    }

    @Test
    fun scopeRunsItsBlockOnTheNewContext() {
        val ctx = itemOf(10)
        val scoped = ctx.scope { update(11) }
        check(10, ctx.value)
        check(11, scoped.value)
    }

    @Test
    fun takeFromAnItemLeavesTheItemAlone() {
        val ctx = itemOf(mapOf("a" to 10, "b" to 15))
        check(10, ctx.take("a").value)
        check(15, ctx.tryTake("b").value)
        assertNull(ctx.tryTake("nope").value)
        check(mapOf("a" to 10, "b" to 15), ctx.value)
    }

    @Test
    fun extractChildValues() {
        val ctx = itemOf(mapOf("a" to 10, "b" to 15))
        assertSame(ctx, ctx.extract("a"))
        check(10, ctx.value)
    }

    @Test
    fun extractPlaysWellWithScope() {
        val ctx = itemOf(mapOf("a" to 10, "b" to 15))
        check(10, ctx.scope().extract("a").value)
        check(15, ctx.scope().extract("b").value)
    }

    @Test
    fun extractFailsOnMissingKey() {
        assertFailsWith<PipelineError> { itemOf(mapOf("a" to 1)).extract("b") }
    }

    @Test
    fun extractHandlesNull() {
        assertNull(itemOf(null).extract(2).value)
    }

    @Test
    fun extractASingleItemFromAList() {
        check(3, itemOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)).extract(2).value)
    }

    @Test
    fun extractSlicesAListWithARange() {
        check(listOf(3, 4, 5), itemOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9)).extract(2..4).value)
    }

    @Test
    fun extractDig() {
        val ctx = itemOf(mapOf("a" to mapOf("b" to listOf("x", "y"))))
        check("y", ctx.extractDig("a", "b", 1).value)
    }

    // endregion

    // region put

    @Test
    fun putNamesTheCurrentValue() {
        val root = testRoot()
        itemOf(5, root).put("five")
        check(mapOf("five" to 5), root.collector)
    }

    @Test
    fun putWithAValueIgnoresTheCurrentValue() {
        val root = testRoot()
        itemOf(5, root).put("six", 6).put("nothing", null)
        check(mapOf("six" to 6, "nothing" to null), root.collector)
    }

    @Test
    fun resetClearsWhatWasPut() {
        val written = mutableListOf<Collector>()
        val root = testRoot(sink = written.asSink())
        val ctx = itemOf(5, root)
        ctx.put("a").put("b", 6)
        check(mapOf("a" to 5, "b" to 6), root.collector)
        ctx.reset()
        check(emptyMap<Any?, Any?>(), root.collector)
        // ... so nothing is written for a record left empty
        ctx.flush()
        check(0, written.size)
        // and putting again starts afresh
        ctx.put("c").flush()
        check(listOf(mapOf("c" to 5)), written)
    }

    @Test
    fun putBuryPutsNestedValues() {
        val root = testRoot(mapOf("a" to 1))
        val ctx = root.take("a")
        ctx.putBury("deeply", "nested", "key")
        check(mapOf("deeply" to mapOf("nested" to mapOf("key" to 1))), root.collector)
    }

    // endregion

    // region parse / format

    @Test
    fun parseInt() {
        check(12, itemOf("12").parseInt().value)
        check(12, itemOf(" 12 ").parseInt().value)
        check(255, itemOf("ff").parseInt(16).value)
        check(12, itemOf(12L).parseInt().value)
        check(12, itemOf(12.7).parseInt().value)
        assertNull(itemOf(null).parseInt().value)
        assertFailsWith<NumberFormatException> { itemOf("twelve").parseInt() }
    }

    @Test
    fun parseDouble() {
        check(1.5, itemOf("1.5").parseDouble().value)
        check(2.0, itemOf(2).parseDouble().value)
        assertNull(itemOf(null).parseDouble().value)
    }

    @Test
    fun parseDateHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.parseDate())
        assertNull(ctx.value)
    }

    @Test
    fun parseDateInIsoFormat() {
        check(LocalDate.of(2021, 9, 1), itemOf("2021-09-01").parseDate().value)
    }

    @Test
    fun parseDateInACustomFormat() {
        check(LocalDate.of(2021, 9, 1), itemOf("09/01/21").parseDate("MM/dd/yy").value)
    }

    @Test
    fun parseDateAcceptsExistingDates() {
        check(LocalDate.of(2021, 9, 1), itemOf(LocalDate.of(2021, 9, 1)).parseDate().value)
        check(LocalDate.of(2021, 9, 1), itemOf(LocalDateTime.of(2021, 9, 1, 3, 4)).parseDate().value)
    }

    @Test
    fun parseDateFailsOnBadInput() {
        assertFailsWith<java.time.format.DateTimeParseException> { itemOf("nope").parseDate() }
    }

    @Test
    fun parseDateTimeHandlesNull() {
        assertNull(itemOf(null).parseDateTime().value)
    }

    @Test
    fun parseDateTimeInTheDefaultFormat() {
        check(LocalDateTime.of(2021, 9, 1, 14, 53, 0), itemOf("2021-09-01 14:53:00").parseDateTime().value)
    }

    @Test
    fun parseDateTimeInACustomFormat() {
        val format = DateTimeFormatter.ofPattern("MM/dd/yy h:mm a", Locale.ENGLISH)
        check(LocalDateTime.of(2021, 9, 1, 14, 53, 0), itemOf("09/01/21 2:53 PM").parseDateTime(format).value)
    }

    @Test
    fun parseDateTimeAcceptsExistingValues() {
        val dt = LocalDateTime.of(2021, 9, 1, 14, 53, 0)
        check(dt, itemOf(dt).parseDateTime().value)
        check(LocalDateTime.of(2021, 9, 1, 0, 0, 0), itemOf(LocalDate.of(2021, 9, 1)).parseDateTime().value)
    }

    @Test
    fun formatDateHandlesNull() {
        assertNull(itemOf(null).formatDate().value)
    }

    @Test
    fun formatDate() {
        check("2024-11-14", itemOf(LocalDate.of(2024, 11, 14)).formatDate().value)
        check("14/11/2024", itemOf(LocalDate.of(2024, 11, 14)).formatDate("dd/MM/yyyy").value)
        check("2024-11-14", itemOf(LocalDateTime.of(2024, 11, 14, 4, 55)).formatDate().value)
    }

    @Test
    fun formatDateRejectsNonDates() {
        assertFailsWith<ValueError> { itemOf("2024-11-14").formatDate() }
    }

    @Test
    fun formatDateTimeHandlesNull() {
        assertNull(itemOf(null).formatDateTime().value)
    }

    @Test
    fun formatDateTime() {
        check("2024-11-14 04:55:00", itemOf(LocalDateTime.of(2024, 11, 14, 4, 55)).formatDateTime().value)
        check("04:55", itemOf(LocalDateTime.of(2024, 11, 14, 4, 55)).formatDateTime("HH:mm").value)
    }

    @Test
    fun parseBooleanHandlesNull() {
        assertNull(itemOf(null).parseBoolean().value)
    }

    @Test
    fun parseBooleanAllowsSeveralValues() {
        check(true, itemOf("true").parseBoolean().value)
        check(true, itemOf("Yes").parseBoolean().value)
        check(true, itemOf(1).parseBoolean().value)
        check(false, itemOf("false").parseBoolean().value)
        check(false, itemOf("No").parseBoolean().value)
        check(false, itemOf("").parseBoolean().value)
        check(false, itemOf(0).parseBoolean().value)
        check(true, itemOf(true).parseBoolean().value)
    }

    @Test
    fun parseBooleanAcceptsCustomValues() {
        val yes = setOf<Any>("Darn Right!")
        val no = setOf<Any>("Ain't No Way!")
        check(true, itemOf("Darn Right!").parseBoolean(yes, no).value)
        check(false, itemOf("Ain't No Way!").parseBoolean(yes, no).value)
    }

    @Test
    fun parseBooleanFailsOnAnUnmatchedValue() {
        assertFailsWith<ValueError> { itemOf("maybe").parseBoolean() }
    }

    @Test
    fun formatBooleanHandlesNull() {
        assertNull(itemOf(null).formatBoolean().value)
    }

    @Test
    fun formatBoolean() {
        check("Yes", itemOf(true).formatBoolean().value)
        check("No", itemOf(false).formatBoolean().value)
        check("Darn Right!", itemOf(true).formatBoolean("Darn Right!", "Ain't No Way!").value)
        check("Ain't No Way!", itemOf(false).formatBoolean("Darn Right!", "Ain't No Way!").value)
        check(1, itemOf(true).formatBoolean(1, 0).value)
    }

    @Test
    fun formatBooleanFailsOnNonBooleans() {
        assertFailsWith<ValueError> { itemOf("x").formatBoolean() }
    }

    @Test
    fun formatString() {
        assertNull(itemOf(null).formatString("%s").value)
        check("1", itemOf(1).formatString("%s").value)
        check("1", itemOf(1).formatString().value)
        check("007", itemOf(7).formatString("%03d").value)
    }

    // endregion

    // region common operations

    @Test
    fun lookupHandlesNull() {
        assertNull(itemOf(null).lookup(mapOf("A" to 1)).value)
    }

    @Test
    fun lookupConvertsAKnownValue() {
        check(1, itemOf("A").lookup(mapOf("A" to 1, "B" to 2)).value)
    }

    @Test
    fun lookupOfAnUnknownValueThrows() {
        assertFailsWith<PipelineError> { itemOf("C").lookup(mapOf("A" to 1)) }
    }

    @Test
    fun lookupCanBeRecoveredToNullOnAnUnknownValue() {
        assertNull(itemOf("C").pipeOrSetNull { lookup(mapOf("A" to 1)) }.value)
    }

    @Test
    fun lookupCanPassTheOriginalValueOnAnUnknownValue() {
        val ctx = itemOf("C").pipeOrRevert { lookup(mapOf("A" to 1)) }
        check("C", ctx.value)
    }

    @Test
    fun lookupCanFallBackToAPipelineOnAnUnknownValue() {
        val ctx = itemOf("C").pipeOrFallback({ lookup(mapOf("A" to 1)) }, { update(3) })
        check(3, ctx.value)
    }

    @Test
    fun lookupCanUseARegisteredLookup() {
        ItemContext.registerLookup("test_lookup_1", mapOf("one" to 1, "two" to 2, "three" to 3))
        check(2, itemOf("two").lookup("test_lookup_1").value)
    }

    @Test
    fun lookupOfAnUnregisteredNameFails() {
        assertFailsWith<PipelineError> { itemOf("two").lookup("no_such_lookup") }
    }

    @Test
    fun stringReplace() {
        assertNull(itemOf(null).stringReplace("a", "b").value)
        check("bininis", itemOf("bananas").stringReplace("a", "i").value)
        check("b1n1n1s", itemOf("bananas").stringReplace(Regex("a"), "1").value)
        check("a-b", itemOf("a  b").stringReplace(Regex("\\s+"), "-").value)
    }

    @Test
    fun strip() {
        assertNull(itemOf(null).strip().value)
        check("x", itemOf("  x \n").strip().value)
    }

    @Test
    fun defaultOverwritesOnlyNull() {
        check(10, itemOf(null).default(10).value)
        check(5, itemOf(5).default(10).value)
        check("", itemOf("").default("x").value)
    }

    @Test
    fun nullIfEmpty() {
        assertNull(itemOf("").nullIfEmpty().value)
        assertNull(itemOf(emptyList<Int>()).nullIfEmpty().value)
        assertNull(itemOf(emptyMap<String, Int>()).nullIfEmpty().value)
        check(" ", itemOf(" ").nullIfEmpty().value)
        check(0, itemOf(0).nullIfEmpty().value)
    }

    @Test
    fun nullIf() {
        assertNull(itemOf("N/A").nullIf("N/A").value)
        check("x", itemOf("x").nullIf("N/A").value)
    }

    @Test
    fun skipAndStopHelpers() {
        assertFailsWith<Skip> { itemOf("").skipIfEmpty() }
        assertFailsWith<Skip> { itemOf("x").skipIf("x") }
        assertFailsWith<Stop> { itemOf(emptyList<Int>()).stopIfEmpty() }
        assertFailsWith<Stop> { itemOf("x").stopIf("x") }
        // ... and pass the item through otherwise
        val ctx = itemOf("y")
        assertSame(ctx, ctx.skipIfEmpty().skipIf("x").stopIfEmpty().stopIf("x"))
    }

    // endregion

    // region strings and lists

    @Test
    fun splitHandlesNull() {
        assertNull(itemOf(null).split(",").value)
    }

    @Test
    fun splitAString() {
        check(listOf("a", "b", "c"), itemOf("a,b,c").split(",").value)
    }

    @Test
    fun splitCanEnterTheResult() {
        val root = testRoot()
        itemOf("Doe, Jane", root).split(", ") {
            take(0).put("last")
            take(1).put("first")
        }
        check(mapOf("last" to "Doe", "first" to "Jane"), root.collector)
    }

    @Test
    fun joinHandlesNull() {
        assertNull(itemOf(null).join(",").value)
    }

    @Test
    fun joinAList() {
        check("1,2,3", itemOf(listOf(1, 2, 3)).join(",").value)
    }

    @Test
    fun joinFailsOnANonList() {
        assertFailsWith<ValueError> { itemOf(5).join(",") }
    }

    @Test
    fun coalesce() {
        assertNull(itemOf(null).coalesce().value)
        assertNull(itemOf(emptyList<Int>()).coalesce().value)
        check(1, itemOf(listOf(1, 2, null, 4)).coalesce().value)
        check(2, itemOf(listOf(null, 2, 3, 4)).coalesce().value)
    }

    @Test
    fun compact() {
        assertNull(itemOf(null).compact().value)
        check(listOf(1, 2), itemOf(listOf(null, 1, null, 2)).compact().value)
    }

    @Test
    fun filter() {
        assertNull(itemOf(null).filter { true }.value)
        check(listOf(2, 4), itemOf(listOf(1, 2, 3, 4)).filter { (it as Int) % 2 == 0 }.value)
    }

    @Test
    fun map() {
        assertNull(itemOf(null).map { it }.value)
        check(listOf(2, 4, 6), itemOf(listOf(1, 2, 3)).map { (it as Int) * 2 }.value)
    }

    @Test
    fun mapApplyHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.mapApply { convert { (it as Int) + 1 } })
        assertNull(ctx.value)
    }

    @Test
    fun mapApplyHandlesAnEmptyList() {
        check(emptyList<Int>(), itemOf(emptyList<Int>()).mapApply { convert { (it as Int) + 1 } }.value)
    }

    @Test
    fun mapApplyAppliesAPipelineToEachElement() {
        check(listOf(2, 3, 4), itemOf(listOf(1, 2, 3)).mapApply { convert { (it as Int) + 1 } }.value)
        check(listOf(1, 2), itemOf(listOf("1", "2")).mapApply { parseInt() }.value)
    }

    @Test
    fun eachSubrecordHandlesNull() {
        itemOf(null).eachSubrecord { throw AssertionError("should not run") }
    }

    @Test
    fun eachSubrecordFailsOnANonList() {
        assertFailsWith<ValueError> { itemOf(5).eachSubrecord { } }
    }

    @Test
    fun eachSubrecordRunsOncePerElement() {
        val seen = mutableListOf<Any?>()
        itemOf(listOf(mapOf("x" to 1), mapOf("x" to 2))).eachSubrecord { seen.add(take("x").value) }
        check(listOf(1, 2), seen)
    }

    @Test
    fun regexHandlesNull() {
        val ctx = itemOf(null)
        assertSame(ctx, ctx.regex(Regex("something")))
        assertNull(ctx.value)
    }

    @Test
    fun regexEntersAMatch() {
        val root = testRoot()
        itemOf("a: 1, b: 2", root).regex(Regex("a: (?<a>\\d+), b: (?<b>\\d+)")) {
            take("a").put("a")
            take("b").put("b")
            take(0).put("whole")
        }
        check(mapOf("a" to "1", "b" to "2", "whole" to "a: 1, b: 2"), root.collector)
    }

    @Test
    fun regexWithoutAMatchIsNull() {
        var entered = false
        val ctx = itemOf("zzz").regex(Regex("\\d+")) { entered = true }
        assertNull(ctx.value)
        check(false, entered)
    }

    // endregion
}
