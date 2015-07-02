/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.language.java
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.test.fixtures.archive.JarTestFixture

class CustomComponentJarBinariesIntegrationTest extends AbstractIntegrationSpec {
    def setup() {
        buildFile << """
            plugins {
                id 'jvm-component'
                id 'java-lang'
            }

import org.gradle.internal.service.ServiceRegistry
import org.gradle.jvm.platform.internal.DefaultJavaPlatform
import org.gradle.platform.base.internal.BinaryNamingSchemeBuilder
import org.gradle.platform.base.internal.DefaultPlatformRequirement
import org.gradle.platform.base.internal.PlatformResolvers
import org.gradle.jvm.toolchain.JavaToolChainRegistry

interface SampleLibrarySpec extends ComponentSpec {}

class DefaultSampleLibrarySpec extends BaseComponentSpec implements SampleLibrarySpec {}

class SampleLibraryRules extends RuleSource {
    @ComponentType
    void register(ComponentTypeBuilder<SampleLibrarySpec> builder) {
        builder.defaultImplementation(DefaultSampleLibrarySpec)
    }

    @ComponentBinaries
    public void createBinaries(ModelMap<JarBinarySpec> binaries, SampleLibrarySpec library,
                               PlatformResolvers platforms, BinaryNamingSchemeBuilder namingSchemeBuilder,
                               @Path("buildDir") File buildDir, ServiceRegistry serviceRegistry, JavaToolChainRegistry toolChains) {
        def binariesDir = new File(buildDir, "jars")
        def classesDir = new File(buildDir, "classes")

        def defaultJavaPlatformName = new DefaultJavaPlatform(JavaVersion.current()).name
        def platformRequirement = DefaultPlatformRequirement.create(defaultJavaPlatformName)
        def platform = platforms.resolve(JavaPlatform, platformRequirement)

        def toolChain = toolChains.getForPlatform(platform)
        def binaryName = namingSchemeBuilder.withComponentName(library.name).withTypeString("jar").build().lifecycleTaskName
        binaries.create(binaryName) { binary ->
            binary.baseName = library.name
            binary.toolChain = toolChain
            binary.targetPlatform = platform

            def outputDir = new File(classesDir, binary.name)
            binary.classesDir = outputDir
            binary.resourcesDir = outputDir
            binary.jarFile = new File(binariesDir, String.format("%s/%s.jar", binary.name, library.name))
        }
    }
}

apply plugin:SampleLibraryRules
        """
    }

    def "custom component defined by plugin is built from Java source" () {
        given:
        file("src/main/java/Java.java") << "public class Java {}"
        file("src/main/resources/java.properties") << "origin=java"
        file("src/main/bin/Bin.java") << "public class Bin {}"
        file("src/main/bin-resources/bin.properties") << "origin=bin"

        buildFile << """
            model {
                components {
                    sampleLib(SampleLibrarySpec) {
                        sources {
                            java(JavaSourceSet) {
                                source.srcDir "src/main/java"
                            }
                            resources(JvmResourceSet) {
                                source.srcDir "src/main/resources"
                            }
                        }
                        binaries {
                            sampleLibJar {
                                sources {
                                    bin(JavaSourceSet) {
                                        source.srcDir "src/main/bin"
                                    }
                                    binResources(JvmResourceSet) {
                                        source.srcDir "src/main/bin-resources"
                                    }
                                }
                            }
                        }
                    }
                }
            }
"""

        when:
        succeeds "assemble"

        then:
        new JarTestFixture(file("build/jars/sampleLibJar/sampleLib.jar")).hasDescendants(
            "Java.class", "java.properties", "Bin.class", "bin.properties");
    }

}
