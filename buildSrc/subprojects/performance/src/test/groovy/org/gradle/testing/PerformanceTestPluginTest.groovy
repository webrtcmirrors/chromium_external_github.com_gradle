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

import org.gradle.plugins.performance.PerformanceTestPlugin
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class PerformanceTestPluginTest extends Specification {
    @Rule
    TemporaryFolder temporaryFolder = new TemporaryFolder()

    def "given a JUnit xml report, allTestsWereSkipped returns true if @tests == @skipped"() {
        expect:
        PerformanceTestPlugin.allTestsWereSkipped(junitXmlWith(2, 2))
    }

    def "given a JUnit xml report, allTestsWereSkipped returns false if @tests != @skipped"() {
        expect:
        !PerformanceTestPlugin.allTestsWereSkipped(junitXmlWith(2, 1))
    }

    def "allTestsWereSkipped returns false if JUnit xml isn't valid"() {
        def junitXml = temporaryFolder.newFile("junit.xml")
        junitXml.text = "broken"
        expect:
        !PerformanceTestPlugin.allTestsWereSkipped()
    }

    private File junitXmlWith(tests, skipped) {
        def junitXml = temporaryFolder.newFile("junit.xml")
        junitXml.text = """<?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="TestSuite" tests="$tests" skipped="$skipped" failures="0" errors="0">
            </testsuite>
        """
        return junitXml
    }
}
