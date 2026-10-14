package org.example

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class RecordingSink(
    val events: MutableList<String> = mutableListOf(),
    private val name: String = "",
    private val failOn: String? = null,
) : Sink {
    private fun event(what: String) {
        events.add(name + what)
        if (what == failOn) throw IllegalStateException("$name$what failed")
    }

    override fun write(collector: Collector) = event("write")
    override fun commit() = event("commit")
    override fun rollback(cause: Throwable) = event("rollback:${cause.message}")
    override fun close() = event("close")
}

private class RecordingSource(
    private val events: MutableList<String>,
    private val records: List<Map<String, Any?>>,
    private val failAfter: Int? = null,
) : Source {
    override fun entries() = records.asSequence().mapIndexed { i, r ->
        if (failAfter != null && i == failAfter) throw SourceError("source broke")
        SourceEntry(i, Record.of(r))
    }

    override fun close() {
        events.add("source close")
    }
}

class TransactionTest {
    private val events = mutableListOf<String>()
    private val records = listOf(mapOf("a" to 1), mapOf("a" to 2))

    @Test
    fun commitsAfterASuccessfulMigration() {
        migrate(RecordingSource(events, records), RecordingSink(events)) { take("a").put("A") }
        check(listOf("write", "write", "commit", "close"), events.filter { it != "source close" })
    }

    @Test
    fun theSourceIsClosedBeforeTheCommit() {
        migrate(RecordingSource(events, records), RecordingSink(events)) { take("a").put("A") }
        check(listOf("write", "write", "source close", "commit", "close"), events)
    }

    @Test
    fun stoppingEarlyIsASuccess() {
        migrate(RecordingSource(events, records), RecordingSink(events)) {
            take("a") { put("A"); stopIf(2) }
        }
        check(listOf("write", "source close", "commit", "close"), events)
    }

    @Test
    fun skippingIsASuccess() {
        migrate(RecordingSource(events, records), RecordingSink(events)) { skip() }
        check(listOf("source close", "commit", "close"), events)
    }

    @Test
    fun anEmptyMigrationStillCommits() {
        migrate(RecordingSource(events, emptyList()), RecordingSink(events)) { }
        check(listOf("source close", "commit", "close"), events)
    }

    @Test
    fun rollsBackWhenTheMigrationFails() {
        val error = assertFailsWith<PipelineError> {
            migrate(RecordingSource(events, records), RecordingSink(events)) {
                take("a").put("A")
                take("typo")
            }
        }
        check(listOf("source close", "rollback:${error.message}", "close"), events)
    }

    @Test
    fun rollsBackWhenAfterSomeRecordsWereWritten() {
        val error = assertFailsWith<IllegalStateException> {
            migrate(RecordingSource(events, records), RecordingSink(events)) {
                take("a").put("A")
                if (index().value == 1) throw IllegalStateException("boom")
            }
        }
        check(listOf("write", "source close", "rollback:boom", "close"), events)
        check("boom", error.message)
    }

    @Test
    fun rollsBackWhenTheSourceFails() {
        assertFailsWith<SourceError> {
            migrate(RecordingSource(events, records, failAfter = 1), RecordingSink(events)) { take("a").put("A") }
        }
        check(listOf("write", "source close", "rollback:source broke", "close"), events)
    }

    @Test
    fun rollsBackWhenTheCommitFails() {
        val error = assertFailsWith<IllegalStateException> {
            migrate(RecordingSource(events, records), RecordingSink(events, failOn = "commit")) { take("a").put("A") }
        }
        check("commit failed", error.message)
        check(listOf("write", "write", "source close", "commit", "rollback:commit failed", "close"), events)
    }


    @Test
    fun aRollbackFailureIsAttachedToTheOriginalError() {
        val sink = object : Sink {
            override fun write(collector: Collector) {}
            override fun rollback(cause: Throwable) = throw IllegalStateException("rollback broke")
        }
        val error = assertFailsWith<PipelineError> {
            migrate(listOf(mapOf("a" to 1)).asSource(), sink) { take("typo") }
        }
        check("rollback broke", error.suppressed.single().message)
    }

    @Test
    fun theSinkIsClosedEvenWhenRollbackFails() {
        var closed = false
        val sink = object : Sink {
            override fun write(collector: Collector) {}
            override fun rollback(cause: Throwable) = throw IllegalStateException("rollback broke")
            override fun close() { closed = true }
        }
        assertFailsWith<PipelineError> { migrate(listOf(mapOf("a" to 1)).asSource(), sink) { take("typo") } }
        assertTrue(closed)
    }

    @Test
    fun sinksWithoutTransactionsAreUnaffected() {
        val out = mutableListOf<Collector>()
        migrate(records, out) { take("a").put("A") }
        check(2, out.size)
        assertFailsWith<PipelineError> { migrate(records, out) { take("typo") } }
    }

    @Test
    fun sinksCanBeUsedWithoutTheDefaultsForCommitAndRollback() {
        val sink = object : Sink {
            override fun write(collector: Collector) {}
        }
        sink.commit()
        sink.rollback(RuntimeException())
    }

    // region MultiSink

    @Test
    fun multiSinkForwardsCommitAndClose() {
        val a = RecordingSink(events, "a:")
        val b = RecordingSink(events, "b:")
        migrate(RecordingSource(events, records.take(1)), MultiSink("a" to a, "b" to b)) { }
        check(listOf("source close", "a:commit", "b:commit", "a:close", "b:close"), events)
    }

    @Test
    fun multiSinkForwardsRollback() {
        val a = RecordingSink(events, "a:")
        val b = RecordingSink(events, "b:")
        assertFailsWith<PipelineError> {
            migrate(RecordingSource(events, records.take(1)), MultiSink("a" to a, "b" to b)) { take("typo") }
        }
        check(
            listOf(
                "source close", "a:rollback:Key 'typo' not found in record", "b:rollback:Key 'typo' not found in record",
                "a:close", "b:close",
            ),
            events,
        )
    }

    @Test
    fun multiSinkRollsBackEveryoneEvenIfOneFails() {
        val a = RecordingSink(events, "a:", failOn = "rollback:Key 'typo' not found in record")
        val b = RecordingSink(events, "b:")
        val error = assertFailsWith<PipelineError> {
            migrate(RecordingSource(events, records.take(1)), MultiSink("a" to a, "b" to b)) { take("typo") }
        }
        assertTrue(events.any { it.startsWith("b:rollback") })
        check("a:rollback:Key 'typo' not found in record failed", error.suppressed.single().message)
    }

    @Test
    fun multiSinkClosesEveryoneEvenIfOneFails() {
        val a = RecordingSink(events, "a:", failOn = "close")
        val b = RecordingSink(events, "b:")
        val sink = MultiSink("a" to a, "b" to b)
        assertFailsWith<IllegalStateException> { sink.close() }
        check(listOf("a:close", "b:close"), events)
    }

    // endregion
}
