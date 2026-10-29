package micdrop.cli

import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.util.isError
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.system.exitProcess

/**
 * Runs a micdrop script: `micdrop my_script.micdrop.kts [args...]`. Returns the process exit code.
 */
fun runScript(file: File, scriptArgs: List<String>): Int {
    val result = BasicJvmScriptingHost().evalWithTemplate<MicdropScript>(
        file.toScriptSource(),
        evaluation = { constructorArgs(scriptArgs.toTypedArray()) },
    )
    for (report in result.reports) {
        if (report.severity >= ScriptDiagnostic.Severity.WARNING) System.err.println(report.render())
    }
    val value = (result as? ResultWithDiagnostics.Success)?.value?.returnValue
    return when {
        value is ResultValue.Error -> {
            // The script itself failed; its exception is the interesting part, not the scripting machinery's
            (value.error).printStackTrace()
            1
        }
        result.isError() -> 1
        else -> 0
    }
}

fun main(args: Array<String>) {
    val file = args.firstOrNull()?.let(::File)
    if (file == null || args[0] in setOf("-h", "--help")) {
        System.err.println("Usage: micdrop <script.micdrop.kts> [args...]")
        exitProcess(if (file == null) 2 else 0)
    }
    if (!file.isFile) {
        System.err.println("micdrop: no such file: $file")
        exitProcess(2)
    }
    exitProcess(runScript(file, args.drop(1)))
}
