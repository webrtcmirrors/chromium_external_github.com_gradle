/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package build

import org.gradle.api.DefaultTask
import org.gradle.api.file.EmptyFileVisitor
import org.gradle.api.file.FileTree
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.RelativePath
import org.gradle.api.specs.Spec
import org.gradle.api.specs.Specs
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import org.gradle.api.internal.file.pattern.PatternMatcherFactory

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import org.gradle.kotlin.dsl.*


/**
 * Patch `kotlin-compiler-embeddable` JAR for use as Kotlin DSL script compiler.
 *
 * This task outputs a classes directory in order to support IntelliJ compiling projects that depend on `:kotlinCompilerEmbeddable`.
 * IntelliJ uses the source set output, not the `:jar`.
 */
@CacheableTask
open class PatchKotlinCompilerEmbeddable : DefaultTask() {

    @Input
    val excludes = project.objects.listProperty<String>()

    @Classpath
    val originalFiles = project.files()

    @Classpath
    val dependencies = project.files()

    @Input
    val dependenciesIncludes = project.objects.mapProperty<String, List<String>>()

    @Classpath
    lateinit var additionalFiles: FileTree

    @OutputDirectory
    val outputDirectory = project.objects.directoryProperty()

    @TaskAction
    @Suppress("unused")
    fun patchKotlinCompilerEmbeddable() =
        patchKotlinCompilerEmbeddable(
            originalFile = originalFiles.single {
                it.name.startsWith("kotlin-compiler-embeddable-")
            },
            patchedDirectory = outputDirectory.asFile.get().apply {
                deleteRecursively()
                mkdirs()
            }
        )

    private
    fun patchKotlinCompilerEmbeddable(originalFile: File, patchedDirectory: File) =
        ZipFile(originalFile).use { originalJar ->
            patch(originalJar, patchedDirectory)
        }

    private
    fun patch(originalJar: ZipFile, patchedDirectory: File) {
        copyFromOriginalApplyingExcludes(originalJar, patchedDirectory)
        copyFromDependenciesApplyingIncludes(patchedDirectory)
        copyAdditionalFiles(patchedDirectory)
    }

    private
    fun copyFromOriginalApplyingExcludes(originalJar: ZipFile, patchedDirectory: File) =
        originalJar.entries().asSequence().filterExcluded().forEach { originalEntry ->
            copyEntry(originalJar, originalEntry, patchedDirectory)
        }

    private
    fun copyFromDependenciesApplyingIncludes(patchedDirectory: File) =
        dependenciesIncludes.get().asSequence()
            .flatMap { (jarPrefix, includes) ->
                val includeSpec = includeSpecFor(includes)
                dependencies.asSequence().filter { it.name.startsWith(jarPrefix) }.map { it to includeSpec }
            }
            .forEach { (includedJarFile, includeSpec) ->
                ZipFile(includedJarFile).use { includedJar ->
                    includedJar.entries().asSequence()
                        .filter { !it.isDirectory && includeSpec.isSatisfiedBy(RelativePath.parse(true, it.name)) }
                        .forEach { includedEntry ->
                            copyEntry(includedJar, includedEntry, patchedDirectory)
                        }
                }
            }

    private
    fun copyAdditionalFiles(patchedDirectory: File) =
        additionalFiles.visit(object : EmptyFileVisitor() {
            override fun visitFile(fileDetails: FileVisitDetails) {
                fileDetails.file.inputStream().buffered().use { input ->
                    patchedDirectory.resolve(fileDetails.relativePath.pathString).apply { parentFile.mkdirs() }.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        })

    private
    fun copyEntry(sourceJar: ZipFile, sourceEntry: ZipEntry, patchedDirectory: File) {
        patchedDirectory.resolve(sourceEntry.name).also { path ->
            if (sourceEntry.isDirectory) path.mkdirs()
            else sourceJar.getInputStream(sourceEntry).buffered().use { input ->
                patchedDirectory.resolve(sourceEntry.name).apply { parentFile.mkdirs() }.outputStream().buffered().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private
    fun Sequence<ZipEntry>.filterExcluded() =
        excludeSpecFor(excludes.get()).let { excludeSpec ->
            filter {
                excludeSpec.isSatisfiedBy(RelativePath.parse(true, it.name))
            }
        }

    private
    fun includeSpecFor(includes: List<String>): Spec<RelativePath> =
        patternSpecFor(includes)

    private
    fun excludeSpecFor(excludes: List<String>): Spec<RelativePath> =
        Specs.negate(patternSpecFor(excludes))

    private
    fun patternSpecFor(patterns: List<String>): Spec<RelativePath> =
        Specs.union(patterns.map {
            PatternMatcherFactory.getPatternMatcher(true, true, it)
        })
}
