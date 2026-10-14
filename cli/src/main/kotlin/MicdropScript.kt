package org.example.cli

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

/**
 * The type of `*.micdrop.kts` scripts. A script sees the micdrop library and everything bundled with the runner, and
 * can read its command-line arguments from [args].
 */
@KotlinScript(fileExtension = "micdrop.kts", compilationConfiguration = MicdropScriptConfiguration::class)
abstract class MicdropScript(val args: Array<String>)

object MicdropScriptConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        "org.example.*",
        "org.example.ext.*",
        "org.example.ext.jooq.*",
        "org.example.ext.jsoup.*",
        DependsOn::class.qualifiedName!!,
        Repository::class.qualifiedName!!,
    )
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    refineConfiguration {
        onAnnotations(DependsOn::class, Repository::class, handler = ::resolveDependencies)
    }
})

private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

private fun resolveDependencies(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
    val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)?.takeIf { it.isNotEmpty() }
        ?: return context.compilationConfiguration.asSuccess()
    return runBlocking { resolver.resolveFromScriptSourceAnnotations(annotations) }.onSuccess { files ->
        ScriptCompilationConfiguration(context.compilationConfiguration) {
            updateClasspath(files)
        }.asSuccess()
    }
}
