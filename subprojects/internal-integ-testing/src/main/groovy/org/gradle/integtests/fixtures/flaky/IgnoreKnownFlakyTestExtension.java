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

import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.SpecInfo;

public class IgnoreKnownFlakyTestExtension extends AbstractAnnotationDrivenExtension<IgnoreKnownFlakyTest> {
    @Override
    public void visitSpecAnnotation(IgnoreKnownFlakyTest ignoreFlaky, SpecInfo spec) {
        for (FeatureInfo feature : spec.getFeatures()) {
            if (!feature.getFeatureMethod().getReflection().isAnnotationPresent(IgnoreKnownFlakyTest.class)) {
                visitFeatureAnnotation(ignoreFlaky, feature);
            }
        }
    }

    @Override
    public void visitFeatureAnnotation(IgnoreKnownFlakyTest ignoreFlaky, FeatureInfo feature) {
        feature.getFeatureMethod().addInterceptor(new IgnoreKnownFlakyTestInterceptor());
    }

    @Override
    public void visitFixtureAnnotation(IgnoreKnownFlakyTest ignoreFlaky, MethodInfo fixtureMethod) {
        fixtureMethod.addInterceptor(new IgnoreKnownFlakyTestInterceptor());
    }
}
