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

package org.gradle.gradlebuild.unittestandcompile

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.os.OperatingSystem


@CacheableTask
open class EnvSpecificTest : Test() {

    @Input
    var operatingSystem = "${OperatingSystem.current().name} ${System.getProperty("os.arch")}"

    @Internal
    var javaVersionOverride = super.getJavaVersion()

    @Input
    override fun getJavaVersion() = javaVersionOverride

    @Internal // differs between Linux and Windows
    override fun getJvmArgs(): MutableList<String> {
        return super.getJvmArgs()
    }
}
