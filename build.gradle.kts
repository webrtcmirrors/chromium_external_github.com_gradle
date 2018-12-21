/*
 * Copyright 2010 the original author or authors.
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

import org.gradle.api.internal.GradleInternal
import org.gradle.build.BuildReceipt
import org.gradle.build.Install
import org.gradle.gradlebuild.ProjectGroups
import org.gradle.modules.PatchExternalModules
import org.gradle.gradlebuild.BuildEnvironment
import org.gradle.gradlebuild.ProjectGroups.implementationPluginProjects
import org.gradle.gradlebuild.ProjectGroups.javaProjects
import org.gradle.gradlebuild.ProjectGroups.pluginProjects
import org.gradle.gradlebuild.ProjectGroups.publishedProjects
import org.gradle.util.GradleVersion
import org.gradle.gradlebuild.buildquality.incubation.IncubatingApiAggregateReportTask
import org.gradle.gradlebuild.buildquality.incubation.IncubatingApiReportTask

plugins {
    `java-base`
    id("com.gradle.distributed") version("0.0.1")
    gradlebuild.`build-types`
    gradlebuild.`ci-reporting`
    // TODO Apply this plugin in the BuildScanConfigurationPlugin once binary plugins can apply plugins via the new plugin DSL
    // We have to apply it here at the moment, so that when the build scan plugin is auto-applied via --scan can detect that
    // the plugin has been already applied. For that the plugin has to be applied with the new plugin DSL syntax.
    com.gradle.`build-scan`
    id("org.gradle.ci.tag-single-build") version("0.48")
}

defaultTasks("assemble")

base.archivesBaseName = "gradle"

buildTypes {
    create("compileAllBuild") {
        tasks(":createBuildReceipt", "compileAll")
        projectProperties("ignoreIncomingBuildReceipt" to true)
    }

    create("sanityCheck") {
        tasks(
            "classes", "doc:checkstyleApi", "codeQuality", "allIncubationReportsZip",
            "docs:check", "distribution:checkBinaryCompatibility", "javadocAll",
            "architectureTest:test", "toolingApi:toolingApiShadedJar")
    }

    // Used by the first phase of the build pipeline, running only last version on multiversion - tests
    create("quickTest") {
        tasks("test", "integTest", "crossVersionTest")
    }

    // Used for builds to run all tests
    create("fullTest") {
        tasks("test", "forkingIntegTest", "forkingCrossVersionTest")
        projectProperties("testAllVersions" to true)
    }

    // Used for builds to test the code on certain platforms
    create("platformTest") {
        tasks("test", "forkingIntegTest", "forkingCrossVersionTest")
        projectProperties("testPartialVersions" to true)
    }

    // Tests not using the daemon mode
    create("noDaemonTest") {
        tasks("noDaemonIntegTest")
        projectProperties("useAllDistribution" to true)
    }

    // Run the integration tests using the parallel executer
    create("parallelTest") {
        tasks("parallelIntegTest")
    }

    create("performanceTests") {
        tasks("performance:performanceTest")
    }

    create("performanceExperiments") {
        tasks("performance:performanceExperiments")
    }

    create("fullPerformanceTests") {
        tasks("performance:fullPerformanceTest")
    }

    create("distributedPerformanceTests") {
        tasks("performance:distributedPerformanceTest")
    }

    create("distributedPerformanceExperiments") {
        tasks("performance:distributedPerformanceExperiment")
    }

    create("distributedFullPerformanceTests") {
        tasks("performance:distributedFullPerformanceTest")
    }

    // Used for cross version tests on CI
    create("allVersionsCrossVersionTest") {
        tasks("allVersionsCrossVersionTests", "integMultiVersionTest")
        projectProperties("testAllVersions" to true)
    }

    create("quickFeedbackCrossVersionTest") {
        tasks("quickFeedbackCrossVersionTests")
    }

    // Used to build production distros and smoke test them
    create("packageBuild") {
        tasks(
            "verifyIsProductionBuildEnvironment", "clean", "buildDists",
            "distributions:integTest")
    }

    // Used to build production distros and smoke test them
    create("promotionBuild") {
        tasks(
            "verifyIsProductionBuildEnvironment", "clean", "docs:check",
            "buildDists", "distributions:integTest", "uploadArchives")
    }

    create("soakTest") {
        tasks("soak:soakTest")
        projectProperties("testAllVersions" to true)
    }

    // Used to run the dependency management engine in "force component realization" mode
    create("forceRealizeDependencyManagementTest") {
        tasks("integForceRealizeTest")
    }
}

allprojects {
    group = "org.gradle"

    repositories {
        maven {
            name = "Gradle libs"
            url = uri("https://repo.gradle.org/gradle/libs")
        }
        maven {
            name = "kotlinx"
            url = uri("https://kotlin.bintray.com/kotlinx/")
        }
        maven {
            name = "kotlin-eap"
            url = uri("https://dl.bintray.com/kotlin/kotlin-eap")
        }
    }

    // patchExternalModules lives in the root project - we need to activate normalization there, too.
    normalization {
        runtimeClasspath {
            ignore("org/gradle/build-receipt.properties")
        }
    }
}

apply(plugin = "gradlebuild.cleanup")
apply(plugin = "gradlebuild.available-java-installations")
apply(plugin = "gradlebuild.buildscan")
apply(from = "gradle/versioning.gradle")
apply(from = "gradle/dependencies.gradle")
apply(plugin = "gradlebuild.minify")
apply(from = "gradle/testDependencies.gradle")
apply(plugin = "gradlebuild.wrapper")
apply(plugin = "gradlebuild.ide")
apply(plugin = "gradlebuild.no-resolution-at-configuration")
apply(plugin = "gradlebuild.update-versions")
apply(plugin = "gradlebuild.dependency-vulnerabilities")
apply(plugin = "gradlebuild.add-verify-production-environment-task")

allprojects {
    apply(plugin = "gradlebuild.dependencies-metadata-rules")
}

distributedBuild {
    excludeTask("prepareVersionsInfo") // TODO why can't we take this from cache?
    excludeTask("jmhRunBytecodeGenerator") // JmhBytecodeGeneratorTask uses absolute path sensitivity

    projectProperty("buildTimestamp", project.ext.get("buildTimestamp") as String)

    val linuxJava8    = buildEnvironment("LinuxJ8","Linux amd64", "Oracle JDK 8")
    val windowsJava8  = buildEnvironment("WindowsJ8","Windows 7 amd64", "Oracle JDK 8")
    val osxJava8      = buildEnvironment("OSXJ8", "Mac OS X x86_64", "Oracle JDK 8")
    val linuxJava11   = buildEnvironment("Linux", "Linux amd64", "OpenJDK 11")
    val windowsJava11 = buildEnvironment("Windows", "Windows 7 amd64", "OpenJDK 11")

    val compileAll = group("compileAll", linuxJava11) {
        match<AbstractCompile>()
        match<IncubatingApiReportTask>()
    }

    val sanityCheck = group("sanityCheck", linuxJava11) {
        match(":docs:checkstyleApi")
        match(":allIncubationReportsZip")
        match(":distributions:checkBinaryCompatibility")
        match(":codeQuality")
        match(":docs:check")
        match(":docs:javadocAll")
        match(":architectureTest:test")
        match(":toolingApi:toolingApiShadedJar")
    }

    val packageBuild = group("packageBuild", linuxJava11) {
        match(":verifyIsProductionBuildEnvironment")
        match(":distributions:buildDists")
        match(":distributions:integTest")
    }

    val smokeTest = group("smokeTest", linuxJava11) {
        match(":smokeTest:smokeTest")
    }

    val quickTest = subprojectDistribution("quickTest") {
        subprojectTask("test")
        subprojectTask("integTest")
        subprojectTask("crossVersionTest")

        excludeProject("architectureTest")
        excludeProject("docs")
        excludeProject("distributions")
        excludeProject("soak")
    }

    val platformTest = subprojectDistribution("platformTest") {
        projectProperty("testPartialVersions", true.toString())

        subprojectTask("test")
        subprojectTask("forkingIntegTest")
        subprojectTask("forkingCrossVersionTest")
    }

    val noDaemonTest = subprojectDistribution("noDaemonTest") {
        projectProperty("useAllDistribution", true.toString())

        subprojectTask("noDaemonIntegTest")
    }

    val forceRealizeDependencyManagementTest = subprojectDistribution("forceRealizeDependencyManagementTest") {
        subprojectTask("integForceRealizeTest")
    }

    val quickFeedbackCrossVersionTest = subprojectDistribution("quickFeedbackCrossVersionTest") {
        subprojectTask("quickFeedbackCrossVersionTests")
    }
    val allVersionsCrossVersionTest = subprojectDistribution("allVersionsCrossVersionTest") {
        projectProperty("testAllVersions", true.toString())

        subprojectTask("allVersionsCrossVersionTests")
        subprojectTask("integMultiVersionTest")
    }

    val parallelTest = subprojectDistribution("parallelTest") {
        subprojectTask("parallelIntegTest")
    }

    val soakTest = subprojectDistribution("soakTest") {
        subprojectTask("soakTest")
    }

    pipeline {
        stage("Compile All Warmup") {
            runAll(compileAll)
        }
        stage("Quick Feedback Linux") {
            runAll(sanityCheck)
            runAll(quickTest, linuxJava11)
        }
        stage("Quick Feedback") {
            runAll(quickTest, windowsJava8)
        }
        stage("Ready for Merge") {
            runAll(platformTest, linuxJava8)
            runAll(platformTest, windowsJava11)
            runAll(packageBuild)
            runAll(smokeTest)
            //TODO runAll(gradleception) - Implement as task in this build
            //TODO performanceTest - Add testDistribution feature which runs each test on a separate agent
        }
        stage("Ready for Nightly") {
            runAll(quickFeedbackCrossVersionTest, linuxJava8)
            runAll(quickFeedbackCrossVersionTest, windowsJava8)
            runAll(parallelTest, linuxJava11)
        }
        stage("Ready for Release") {
            runAll(soakTest, linuxJava11)
            runAll(soakTest, windowsJava8)
            runAll(allVersionsCrossVersionTest, linuxJava8)
            runAll(allVersionsCrossVersionTest, windowsJava8)
            runAll(noDaemonTest, linuxJava8)
            runAll(noDaemonTest, windowsJava11)
            runAll(platformTest, osxJava8)
            runAll(forceRealizeDependencyManagementTest, linuxJava8)
            //TODO performanceExperiments
        }
    }
}

subprojects {
    version = rootProject.version

    if (project in javaProjects) {
        apply(plugin = "gradlebuild.java-projects")
    }

    if (project in publishedProjects) {
        apply(plugin = "gradlebuild.publish-public-libraries")
    }

    apply(from = "$rootDir/gradle/shared-with-buildSrc/code-quality-configuration.gradle.kts")
    apply(plugin = "gradlebuild.task-properties-validation")
    apply(plugin = "gradlebuild.test-files-cleanup")
}

val runtimeUsage = objects.named(Usage::class.java, Usage.JAVA_RUNTIME)

val coreRuntime by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage)
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
}

val coreRuntimeExtensions by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage)
    isCanBeResolved = true
    isCanBeConsumed = false
    isVisible = false
}

val externalModules by configurations.creating {
    isVisible = false
}

/**
 * Configuration used to resolve external modules before patching them with versions from core runtime
 */
val externalModulesRuntime by configurations.creating {
    isVisible = false
    extendsFrom(coreRuntime)
    extendsFrom(externalModules)
}

/**
 * Combines the 'coreRuntime' with the patched external module jars
 */
val runtime by configurations.creating {
    isVisible = false
    extendsFrom(coreRuntime)
}

val gradlePlugins by configurations.creating {
    isVisible = false
}

val testRuntime by configurations.creating {
    extendsFrom(runtime)
    extendsFrom(gradlePlugins)
}

// TODO: These should probably be all collapsed into a single variant
configurations {
    create("gradleApiMetadataElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(runtime)
        extendsFrom(gradlePlugins)
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "metadata")
    }
}
configurations {
    create("gradleApiRuntimeElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(externalModules)
        extendsFrom(gradlePlugins)
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "runtime")
    }
}
configurations {
    create("gradleApiCoreElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(runtime)
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "core")
    }
}
configurations {
    create("gradleApiCoreExtensionsElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(coreRuntime)
        extendsFrom(coreRuntimeExtensions)
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "core-ext")
    }
}
configurations {
    create("gradleApiPluginElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        extendsFrom(gradlePlugins)
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "plugins")
    }
}
configurations {
    create("gradleApiReceiptElements") {
        isVisible = false
        isCanBeResolved = false
        isCanBeConsumed = true
        attributes.attribute(Attribute.of("org.gradle.api", String::class.java), "build-receipt")

        // TODO: Update BuildReceipt to retain dependency information by using Provider
        val createBuildReceipt = tasks.named("createBuildReceipt", BuildReceipt::class.java)
        val receiptFile = createBuildReceipt.map {
            it.receiptFile
        }
        outgoing.artifact(receiptFile) {
            builtBy(createBuildReceipt)
        }
    }
}

configurations {
    all {
        attributes.attribute(Usage.USAGE_ATTRIBUTE, runtimeUsage)
    }
}

extra["allTestRuntimeDependencies"] = testRuntime.allDependencies

val patchedExternalModulesDir = buildDir / "external/files"
val patchedExternalModules = files(provider { fileTree(patchedExternalModulesDir).files.sorted() })
patchedExternalModules.builtBy("patchExternalModules")

dependencies {

    externalModules("org.gradle:gradle-kotlin-dsl:${BuildEnvironment.gradleKotlinDslVersion}")
    externalModules("org.gradle:gradle-kotlin-dsl-provider-plugins:${BuildEnvironment.gradleKotlinDslVersion}")
    externalModules("org.gradle:gradle-kotlin-dsl-tooling-builders:${BuildEnvironment.gradleKotlinDslVersion}")

    coreRuntime(project(":launcher"))
    coreRuntime(project(":runtimeApiInfo"))

    runtime(project(":wrapper"))
    runtime(project(":installationBeacon"))
    runtime(patchedExternalModules)

    pluginProjects.forEach { gradlePlugins(it) }
    implementationPluginProjects.forEach { gradlePlugins(it) }

    gradlePlugins(project(":workers"))
    gradlePlugins(project(":dependencyManagement"))
    gradlePlugins(project(":testKit"))

    coreRuntimeExtensions(project(":dependencyManagement")) //See: DynamicModulesClassPathProvider.GRADLE_EXTENSION_MODULES
    coreRuntimeExtensions(project(":pluginUse"))
    coreRuntimeExtensions(project(":workers"))
    coreRuntimeExtensions(patchedExternalModules)

    testRuntime(project(":apiMetadata"))
}

extra["allCoreRuntimeExtensions"] = coreRuntimeExtensions.allDependencies

tasks.register<PatchExternalModules>("patchExternalModules") {
    allModules = externalModulesRuntime
    coreModules = coreRuntime
    modulesToPatch = this@Build_gradle.externalModules
    destination = patchedExternalModulesDir
    outputs.doNotCacheIfSlowInternetConnection()
}

evaluationDependsOn(":distributions")

val gradle_installPath: Any? = findProperty("gradle_installPath")

tasks.register<Install>("install") {
    description = "Installs the minimal distribution into directory $gradle_installPath"
    group = "build"
    with(distributionImage("binDistImage"))
    installDirPropertyName = ::gradle_installPath.name
}

tasks.register<Install>("installAll") {
    description = "Installs the full distribution into directory $gradle_installPath"
    group = "build"
    with(distributionImage("allDistImage"))
    installDirPropertyName = ::gradle_installPath.name
}

fun distributionImage(named: String) =
        project(":distributions").property(named) as CopySpec

val allIncubationReports = tasks.register<IncubatingApiAggregateReportTask>("allIncubationReports") {
    val allReports = collectAllIncubationReports()
    dependsOn(allReports)
    reports = allReports.associateBy({ it.title.get()}) { it.textReportFile.asFile.get() }
}
tasks.register<Zip>("allIncubationReportsZip") {
    destinationDir = file("$buildDir/reports/incubation")
    baseName = "incubating-apis"
    from(allIncubationReports.get().htmlReportFile)
    from(collectAllIncubationReports().map { it.htmlReportFile })
}

fun Project.collectAllIncubationReports() = subprojects.flatMap { it.tasks.withType(IncubatingApiReportTask::class) }
