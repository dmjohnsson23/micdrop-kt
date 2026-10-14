package org.example

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StructureBuilderTest {
    private val root: Collector = linkedMapOf()

    private fun bury(value: Any?, vararg keys: Any?) = org.example.bury(root, keys.toList(), value)

    @Test
    fun setsASimpleKey() {
        bury("ok", "simple")
        check(mapOf("simple" to "ok"), root)
    }

    @Test
    fun setsMultipleSimpleKeys() {
        bury("ok", "simple")
        bury("yep", "again")
        check(mapOf("simple" to "ok", "again" to "yep"), root)
    }

    @Test
    fun setsADeeplyNestedKey() {
        bury("ok", "deeply", "nested", "key")
        check(mapOf("deeply" to mapOf("nested" to mapOf("key" to "ok"))), root)
    }

    @Test
    fun siblingsShareParents() {
        bury(1, "p", "a")
        bury(2, "p", "b")
        check(mapOf("p" to mapOf("a" to 1, "b" to 2)), root)
    }

    @Test
    fun pushesOntoAListWithNull() {
        bury(1, "array", null)
        bury(2, "array", null)
        check(mapOf("array" to listOf(1, 2)), root)
    }

    @Test
    fun allowsExplicitListIndicesInAnyOrder() {
        bury("two", "array", 2)
        bury("zero", "array", 0)
        bury("one", "array", 1)
        check(mapOf("array" to listOf("zero", "one", "two")), root)
    }

    @Test
    fun padsSkippedIndicesWithNull() {
        bury("two", "array", 2)
        check(mapOf("array" to listOf(null, null, "two")), root)
    }

    @Test
    fun allowsAMapKeyAfterAListPush() {
        bury("ok", "array", null, "item")
        check(mapOf("array" to listOf(mapOf("item" to "ok"))), root)
    }

    @Test
    fun canReferenceThePreviousListItemWithANegativeIndex() {
        bury("ok", "array", null, "item")
        bury(5, "array", -1, "coolness")
        check(mapOf("array" to listOf(mapOf("item" to "ok", "coolness" to 5))), root)
    }

    @Test
    fun createsASecondItemWithoutANegativeIndex() {
        bury("ok", "array", null, "item")
        bury(5, "array", null, "coolness")
        check(mapOf("array" to listOf(mapOf("item" to "ok"), mapOf("coolness" to 5))), root)
    }

    @Test
    fun supportsChainedPushes() {
        bury("first", "x", null, null, null)
        bury("second", "x", null, null, null)
        check(mapOf("x" to listOf(listOf(listOf("first")), listOf(listOf("second")))), root)
    }

    @Test
    fun putsNullValues() {
        bury(null, "a", "b")
        check(mapOf("a" to mapOf("b" to null)), root)
    }

    @Test
    fun overwritesAnExistingLeaf() {
        bury(1, "a")
        bury(2, "a")
        check(mapOf("a" to 2), root)
    }

    @Test
    fun failsWhenBuryingThroughAScalar() {
        bury(1, "a")
        assertFailsWith<PipelineError> { bury(2, "a", "b") }
    }

    @Test
    fun failsOnAppendToAMap() {
        assertFailsWith<PipelineError> { bury(1, null) }
    }

    @Test
    fun failsOnAMapKeyInAList() {
        bury(1, "a", null)
        assertFailsWith<PipelineError> { bury(2, "a", "key") }
    }

    @Test
    fun failsWithNoKeys() {
        assertFailsWith<PipelineError> { bury(1) }
    }

    @Test
    fun failsOnANegativeIndexOutOfRange() {
        bury(1, "a", null)
        assertFailsWith<PipelineError> { bury(2, "a", -5) }
    }
}
