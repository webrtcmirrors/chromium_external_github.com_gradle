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

package org.gradle.integtests.fixtures.flaky;

import org.junit.Assume;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

public class IgnoreKnownFlakyTestRule implements TestRule {
    private static final List<String> KNOWN_FLAKY_TEST_MESSAGES = Arrays.asList(
        "An existing connection was forcibly closed by the remote host",
        "An established connection was aborted by the software in your host machine", // https://github.com/gradle/gradle-private/issues/763
        "zip END header not found" // https://github.com/gradle/gradle-private/issues/1537
    );

    @Override
    public Statement apply(Statement base, Description description) {
        return new IgnoreKnowFlakyTestStatement(base);
    }

    private class IgnoreKnowFlakyTestStatement extends Statement {
        private final Statement next;

        public IgnoreKnowFlakyTestStatement(Statement next) {
            this.next = next;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                next.evaluate();
            } catch (Throwable e) {
                Assume.assumeFalse(isKnownFlakyTest(e));
            }
        }
    }

    public static boolean isKnownFlakyTest(Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String stacktrace = sw.toString();

        return KNOWN_FLAKY_TEST_MESSAGES.stream().anyMatch(stacktrace::contains);
    }
}
