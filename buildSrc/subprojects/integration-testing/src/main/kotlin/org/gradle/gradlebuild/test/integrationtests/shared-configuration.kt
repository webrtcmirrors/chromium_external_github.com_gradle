package org.gradle.gradlebuild.test.integrationtests

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider

import org.gradle.kotlin.dsl.*

import accessors.eclipse
import accessors.groovy
import accessors.idea
import accessors.java
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion

import org.gradle.gradlebuild.java.AvailableJavaInstallations
import org.gradle.internal.os.OperatingSystem
import org.gradle.plugins.ide.eclipse.EclipsePlugin
import org.gradle.plugins.ide.idea.IdeaPlugin


enum class TestType(val prefix: String, val executers: List<String>, val libRepoRequired: Boolean) {
    INTEGRATION("integ", listOf("embedded", "forking", "noDaemon", "parallel"), false),
    CROSSVERSION("crossVersion", listOf("embedded", "forking"), true)
}


data class BuildEnvironment(val name: String, val os: String, val vm: String) {
    fun asEnvironmentSpecificName(baseName: String) = "$baseName$name"
}


val buildEnvironments = listOf(
    BuildEnvironment("LinuxJ8", "Linux amd64", "OpenJDK 8"),
    BuildEnvironment("WindowsJ8", "Windows 7 amd64", "Oracle JDK 8"),
    BuildEnvironment("OSXJ8", "Mac OS X x86_64", "Oracle JDK 8"),
    BuildEnvironment("Linux", "Linux amd64", "OpenJDK 11"),
    BuildEnvironment("Windows", "Windows 7 amd64", "OpenJDK 11")
)


internal
fun Project.addDependenciesAndConfigurations(testType: TestType) {
    val prefix = testType.prefix
    configurations {
        getByName("${prefix}TestCompile") { extendsFrom(configurations["testCompile"]) }
        getByName("${prefix}TestRuntime") { extendsFrom(configurations["testRuntime"]) }
        getByName("${prefix}TestImplementation") { extendsFrom(configurations["testImplementation"]) }
        getByName("${prefix}TestRuntimeOnly") { extendsFrom(configurations["testRuntimeOnly"]) }
        getByName("partialDistribution") { extendsFrom(configurations["${prefix}TestRuntimeClasspath"]) }
    }

    dependencies {
        "${prefix}TestCompile"(project(":internalIntegTesting"))

        // so that implicit help tasks are available:
        "${prefix}TestRuntime"(project(":diagnostics"))

        // So that the wrapper and init task are added when integTests are run via commandline
        "${prefix}TestRuntime"(project(":buildInit"))
    }
}


internal
fun Project.addSourceSet(testType: TestType): SourceSet {
    val prefix = testType.prefix
    val main by java.sourceSets.getting
    return java.sourceSets.create("${prefix}Test") {
        compileClasspath += main.output
        runtimeClasspath += main.output
    }
}


internal
fun Project.createTasks(sourceSet: SourceSet, testType: TestType) {
    val prefix = testType.prefix
    val defaultExecuter = project.findProperty("defaultIntegTestExecuter") as? String ?: "embedded"

    // For all of the other executers, add an executer specific task
    testType.executers.forEach { executer ->
        val taskName = "$executer${prefix.capitalize()}Test"
        createTestTask(taskName, executer, sourceSet, testType, Action {
            if (testType == TestType.CROSSVERSION) {
                // the main crossVersion test tasks always only check the latest version,
                // for true multi-version testing, we set up a test task per Gradle version,
                // (see CrossVersionTestsPlugin).
                systemProperties["org.gradle.integtest.versions"] = "default"
            }
        })
    }
    // Use the default executer for the simply named task. This is what most developers will run when running check
    val testTask = createTestTask(prefix + "Test", defaultExecuter, sourceSet, testType, Action {})
    // Create a variant of the test suite to force realization of component metadata
    if (testType == TestType.INTEGRATION) {
        createTestTask(prefix + "ForceRealizeTest", defaultExecuter, sourceSet, testType, Action {
            systemProperties["org.gradle.integtest.force.realize.metadata"] = "true"
        })
    }
    tasks.named("check").configure { dependsOn(testTask) }
}


internal
fun Project.createTestTask(name: String, executer: String, sourceSet: SourceSet, testType: TestType, extraConfig: Action<DistributionTest>): TaskProvider<EnvNeutralIntegrationTest> =
    tasks.register(name, EnvNeutralIntegrationTest::class) {
        description = "Runs ${testType.prefix} with $executer executer"
        systemProperties["org.gradle.integtest.executer"] = executer
        addDebugProperties()
        testClassesDirs = sourceSet.output.classesDirs
        classpath = sourceSet.runtimeClasspath
        libsRepository.required = testType.libRepoRequired
        extraConfig.execute(this)
    }.also {
        buildEnvironments.forEach { environment ->
            tasks.register(environment.asEnvironmentSpecificName(name), EnvSpecificIntegrationTest::class) {
                description = "Runs ${testType.prefix} with $executer executer"
                systemProperties["org.gradle.integtest.executer"] = executer
                addDebugProperties()
                testClassesDirs = sourceSet.output.classesDirs
                classpath = sourceSet.runtimeClasspath
                libsRepository.required = testType.libRepoRequired
                extraConfig.execute(this)
                operatingSystem = environment.os
                val vmParts = environment.vm.split(" ")
                javaVersionOverride = JavaVersion.toVersion(vmParts[vmParts.size - 1])
                inputs.property("javaInstallation", environment.vm)
            }.configure {
                doFirst {
                    val currentVM = rootProject.availableJavaInstallations.javaInstallationForTest.vendorAndMajorVersion
                    val currentOS = "${OperatingSystem.current().name} ${System.getProperty("os.arch")}"
                    if (environment.os != currentOS || environment.vm != currentVM) {
                        throw GradleException("This task needs to run on '${environment.os} ${environment.vm}' results need to be taken from cache (currently running on '$currentOS $currentVM')")
                    }
                }
            }
        }
    }


val Project.availableJavaInstallations
    get() = extensions.getByName<AvailableJavaInstallations>("availableJavaInstallations")


private
fun DistributionTest.addDebugProperties() {
    // TODO Move magic property out
    if (project.hasProperty("org.gradle.integtest.debug")) {
        systemProperties["org.gradle.integtest.debug"] = "true"
        testLogging.showStandardStreams = true
    }
    // TODO Move magic property out
    if (project.hasProperty("org.gradle.integtest.verbose")) {
        testLogging.showStandardStreams = true
    }
    // TODO Move magic property out
    if (project.hasProperty("org.gradle.integtest.launcher.debug")) {
        systemProperties["org.gradle.integtest.launcher.debug"] = "true"
    }
}


internal
fun Project.configureIde(testType: TestType) {
    val prefix = testType.prefix
    val compile = configurations.getByName("${prefix}TestCompileClasspath")
    val runtime = configurations.getByName("${prefix}TestRuntimeClasspath")
    val sourceSet = java.sourceSets.getByName("${prefix}Test")

    // We apply lazy as we don't want to depend on the order
    plugins.withType<IdeaPlugin> {
        idea {
            module {
                testSourceDirs = testSourceDirs + sourceSet.java.srcDirs
                testSourceDirs = testSourceDirs + sourceSet.groovy.srcDirs
                testResourceDirs = testResourceDirs + sourceSet.resources.srcDirs
                scopes["TEST"]!!["plus"]!!.apply {
                    add(compile)
                    add(runtime)
                }
            }
        }
    }

    plugins.withType<EclipsePlugin> {
        eclipse {
            classpath.plusConfigurations.apply {
                add(compile)
                add(runtime)
            }
        }
    }
}


internal
val Project.currentTestJavaVersion
    get() = rootProject.the<AvailableJavaInstallations>().javaInstallationForTest.javaVersion
