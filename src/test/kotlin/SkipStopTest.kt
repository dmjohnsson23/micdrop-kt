package micdrop

import kotlin.test.Test

class SkipStopTest {
    private val source = (1..10).map { mapOf("a" to it) }

    private fun run(block: RootRecordContext.() -> Unit) = runMigration(source, block)

    private fun expected(vararg values: Int) = values.map { mapOf("a" to it) }

    @Test
    fun stopFromTop() {
        val out = run {
            val a = take("a")
            if (a.value == 6) stop()
            a.put("a")
        }
        check(expected(1, 2, 3, 4, 5), out)
    }

    @Test
    fun skipFromTop() {
        val out = run {
            val a = take("a")
            if (a.value in listOf(1, 3, 6, 9)) skip()
            a.put("a")
        }
        check(expected(2, 4, 5, 7, 8, 10), out)
    }

    @Test
    fun stopFromTakeBlock() {
        val out = run {
            take("a") {
                if (value == 6) stop()
                put("a")
            }
        }
        check(expected(1, 2, 3, 4, 5), out)
    }

    @Test
    fun skipFromTakeBlock() {
        val out = run {
            take("a") {
                if (value in listOf(1, 3, 6, 9)) skip()
                put("a")
            }
        }
        check(expected(2, 4, 5, 7, 8, 10), out)
    }

    @Test
    fun stopFromApply() {
        val pipeline: Pipeline = { stopIf(6) }
        val out = run { take("a").pipe(pipeline).put("a") }
        check(expected(1, 2, 3, 4, 5), out)
    }

    @Test
    fun skipFromApply() {
        val pipeline: Pipeline = { if (value in listOf(1, 3, 6, 9)) skip() }
        val out = run { take("a").pipe(pipeline).put("a") }
        check(expected(2, 4, 5, 7, 8, 10), out)
    }

    @Test
    fun skipDiscardsAnythingAlreadyPut() {
        val out = run {
            take("a").put("early")
            take("a") { skipIf(2) }
        }
        check(source.filter { it["a"] != 2 }.map { mapOf("early" to it["a"]) }, out)
    }

    @Test
    fun skipFromInsideASubrecord() {
        val out = runMigration(listOf(mapOf("xs" to listOf(mapOf("x" to 1), mapOf("x" to 2))))) {
            take("xs").eachSubrecord { skip() }
        }
        check(emptyList<Any?>(), out)
    }

    @Test
    fun skipAndStopCarryNoStackTrace() {
        check(0, Skip().stackTrace.size)
        check(0, Stop().stackTrace.size)
    }
}
