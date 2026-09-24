import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Kotlin-биндинги ядра в [outputDir]. Генератор читает метаданные UniFFI из
 * библиотеки, собранной под машину сборки, — поэтому компиляции Kotlin и
 * JVM-тестам нужен Rust, но не NDK.
 */
abstract class UniffiKotlin : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun generate() {
        val core = workspace.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = core
            commandLine("cargo", "build", "--locked", "-p", "plinth-ffi")
        }
        // plinth_ffi.dll, libplinth_ffi.so или libplinth_ffi.dylib — по машине сборки.
        val library = core.resolve("target/debug/${System.mapLibraryName("plinth_ffi")}")
        exec.exec {
            workingDir = core
            commandLine(
                "cargo",
                "run",
                "--locked",
                "--quiet",
                "-p",
                "uniffi-bindgen",
                "--",
                "generate",
                "--library",
                library.absolutePath,
                "--language",
                "kotlin",
                "--no-format",
                "--out-dir",
                out.absolutePath,
            )
        }
    }
}
