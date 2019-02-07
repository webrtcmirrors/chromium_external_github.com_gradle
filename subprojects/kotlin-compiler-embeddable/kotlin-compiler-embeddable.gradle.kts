import build.CheckKotlinCompilerEmbeddableDependencies
import build.PatchKotlinCompilerEmbeddable
import build.futureKotlin
import build.kotlinVersion
import org.apache.commons.io.FileUtils
import org.gradle.gradlebuild.unittestandcompile.ModuleType
import java.io.IOException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

plugins {
    `kotlin-library`
}

description = "Kotlin Compiler Embeddable - patched for Gradle"

base.archivesBaseName = "kotlin-compiler-embeddable-$kotlinVersion-patched-for-gradle"

gradlebuildJava {
    moduleType = ModuleType.INTERNAL
}

dependencies {

    api(project(":distributionsDependencies"))

    compile(futureKotlin("stdlib"))
    compile(futureKotlin("reflect"))
    compile(futureKotlin("script-runtime"))

    runtime("org.jetbrains.intellij.deps:trove4j:1.0.20181211")
}

val kotlinCompilerEmbeddableConfiguration = configurations.detachedConfiguration(
    project.dependencies.project(":distributionsDependencies"),
    project.dependencies.create(futureKotlin("compiler-embeddable"))
)

tasks {

    val checkKotlinCompilerEmbeddableDependencies by registering(CheckKotlinCompilerEmbeddableDependencies::class) {
        current.from(configurations.runtimeClasspath)
        expected.from(kotlinCompilerEmbeddableConfiguration)
    }

    val patchedClassesDir = layout.buildDirectory.dir("patched-classes")

    val patchKotlinCompilerEmbeddable by registering(PatchKotlinCompilerEmbeddable::class) {
        dependsOn(checkKotlinCompilerEmbeddableDependencies)
        excludes.set(listOf(
            "META-INF/services/javax.annotation.processing.Processor",
            "META-INF/native/**/*jansi.*"
        ))
        originalFiles.from(kotlinCompilerEmbeddableConfiguration)
        dependencies.from(configurations.detachedConfiguration(
            project.dependencies.project(":distributionsDependencies"),
            project.dependencies.create(library("jansi"))
        ))
        dependenciesIncludes.set(mapOf(
            "jansi-" to listOf("META-INF/native/**", "org/fusesource/jansi/internal/CLibrary*.class")
        ))
        additionalFiles = fileTree(classpathManifest.get().manifestFile.parentFile) {
            include(classpathManifest.get().manifestFile.name)
        }
        outputDirectory.set(patchedClassesDir)
    }

    sourceSets.main {
        output.dir(files(patchedClassesDir).builtBy(patchKotlinCompilerEmbeddable))
    }

    clean {
        doFirst {
            targetFiles.forEach { target ->

                val retries = 1
                val backoffMs = 100L

                var done = false
                var retry = 1
                while (!done && retry <= retries) {
                    System.err.println("DELETING $target TRY $retry")
                    try {
                        target.helpfulDeleteRecursively()
                        done = true
                    } catch (ex: Exception) {
                        if (retry == retries) {
                            throw ex
                        } else {
                            System.err.println("UNABLE TO DELETE $target TRY $retry")
                            ex.printStackTrace()
                            Thread.sleep(backoffMs)
                        }
                    }
                    retry++
                }
            }
        }
    }
}

fun File.helpfulDeleteRecursively() {
    if (isDirectory) {
        if (FileUtils.isSymlink(this)) {
            if (!delete()) throw IOException("Unable to delete symlink: $canonicalPath")
        } else {
            val errorPaths = mutableListOf<String>()
            Files.walkFileTree(toPath(), object : SimpleFileVisitor<Path>() {

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!file.toFile().delete()) errorPaths.add(file.toFile().canonicalPath)
                    return super.visitFile(file, attrs)
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (!dir.toFile().delete()) errorPaths.add(dir.toFile().canonicalPath)
                    return super.postVisitDirectory(dir, exc)
                }
            })
            if (errorPaths.isNotEmpty()) {
                val errors = errorPaths.joinToString(separator = "\n\t- ", prefix = "\t- ")
                val remaining = walk().joinToString(separator = "\n\t- ", prefix = "\t- ") { it.canonicalPath }
                throw IOException("Unable to recursively delete directory $canonicalPath, failed paths:\n$errors\nRemaining files:\n$remaining\n")
            }
        }
    } else if (exists()) {
        if (!delete()) throw IOException("Unable to delete file: $canonicalPath")
    }
}
