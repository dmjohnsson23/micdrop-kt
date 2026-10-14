package org.example.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunScriptTest {
    private val dir = createTempDirectory("micdrop-cli").toFile().also { it.deleteOnExit() }

    private fun resource(name: String) = File(javaClass.getResource("/$name")!!.toURI())

    @Test
    fun `runs a script with access to the library and its arguments`() {
        val output = File(dir, "out.txt")
        val people = File("../examples/data/people-100.csv").absolutePath

        val exitCode = runScript(resource("count_people.micdrop.kts"), listOf(people, output.path))

        assertEquals(0, exitCode)
        assertEquals("100", output.readText())
    }

    @Test
    fun `fails when the script does not compile`() {
        val script = File(dir, "broken.micdrop.kts").apply { writeText("thisDoesNotExist()") }
        assertEquals(1, runScript(script, emptyList()))
    }

    @Test
    fun `fails when the script throws`() {
        val script = File(dir, "throws.micdrop.kts").apply { writeText("error(\"boom\")") }
        assertTrue(runScript(script, emptyList()) != 0)
    }
}
