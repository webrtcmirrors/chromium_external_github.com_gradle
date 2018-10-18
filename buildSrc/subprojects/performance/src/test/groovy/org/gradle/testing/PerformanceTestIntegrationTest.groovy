/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.testing

class PerformanceTestIntegrationTest extends AbstractIntegrationTest {
    def "honors branch name in channel"() {
        buildFile << """
            plugins {
                id 'gradlebuild.int-test-image'
                id 'gradlebuild.performance-test'
            }
            
            def distributedPerformanceTests = tasks.withType(org.gradle.testing.DistributedPerformanceTest)
            distributedPerformanceTests.all {
                // resolve these tasks
            }
            task assertChannel {
                doLast {
                    distributedPerformanceTests.each { distributedPerformanceTest ->
                        assert distributedPerformanceTest.channel.endsWith("-branch")
                    }
                }
            }
        """

        file("internalPerformanceTesting/build.gradle") << "apply plugin: 'java'"
        settingsFile << """
            include 'internalPerformanceTesting'
        """
        expect:
        build("assertChannel", "-Porg.gradle.performance.branchName=branch")
    }
}
