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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.reflect.TypeToken;
import org.gradle.api.artifacts.transform.ArtifactTransformAction;
import org.gradle.api.artifacts.transform.PrimaryInput;
import org.gradle.api.artifacts.transform.PrimaryInputDependencies;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.internal.attributes.ImmutableAttributes;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.internal.tasks.properties.ParameterValidationContext;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.api.reflect.InjectionPointQualifier;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprinterRegistry;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.instantiation.InstanceFactory;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.Isolatable;
import org.gradle.internal.service.ServiceLookup;
import org.gradle.internal.service.ServiceLookupException;
import org.gradle.internal.service.UnknownServiceException;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;

public class DefaultTransformer extends AbstractTransformer<ArtifactTransformAction> {

    private final Isolatable<?> parameterObject;
    private final ImmutableSortedMap<String, DefaultTransformationRegistration.FileInputParameter> inputFiles;
    private final PropertyWalker propertyWalker;
    private final boolean requiresDependencies;
    private final InstanceFactory<? extends ArtifactTransformAction> instanceFactory;

    public DefaultTransformer(Class<? extends ArtifactTransformAction> implementationClass, Isolatable<?> parameterObject, HashCode inputsHash, InstantiatorFactory instantiatorFactory, ImmutableAttributes fromAttributes, ImmutableSortedMap<String, DefaultTransformationRegistration.FileInputParameter> inputFiles, PropertyWalker propertyWalker) {
        super(implementationClass, inputsHash, fromAttributes);
        this.parameterObject = parameterObject;
        this.inputFiles = inputFiles;
        this.propertyWalker = propertyWalker;
        this.instanceFactory = instantiatorFactory.injectScheme(ImmutableSet.of(PrimaryInput.class, PrimaryInputDependencies.class, TransformParameters.class)).forType(implementationClass);
        this.requiresDependencies = instanceFactory.serviceInjectionTriggeredByAnnotation(PrimaryInputDependencies.class);
    }

    public boolean requiresDependencies() {
        return requiresDependencies;
    }

    @Override
    public ImmutableList<File> transform(File primaryInput, File outputDir, ArtifactTransformDependencies dependencies) {
        ArtifactTransformAction transformAction = newTransformAction(primaryInput, dependencies);
        DefaultArtifactTransformOutputs transformOutputs = new DefaultArtifactTransformOutputs(outputDir);
        transformAction.transform(transformOutputs);
        ImmutableList<File> outputs = transformOutputs.getRegisteredOutputs();
        return validateOutputs(primaryInput, outputDir, outputs);
    }

    @Override
    public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileFingerprints(File primaryInput, ArtifactTransformDependencies dependencies, FileCollectionFingerprinterRegistry fileCollectionFingerprinterRegistry, PathToFileResolver resolver) {
        ImmutableSortedMap.Builder<String, CurrentFileCollectionFingerprint> builder = ImmutableSortedMap.naturalOrder();
        for (Map.Entry<String, DefaultTransformationRegistration.FileInputParameter> entry : inputFiles.entrySet()) {
            DefaultTransformationRegistration.FileInputParameter inputParameter = entry.getValue();
            CurrentFileCollectionFingerprint fingerprint = fingerprintInput(inputParameter.getValue(), inputParameter.getFileNormalizer(), inputParameter.getFilePropertyType(), fileCollectionFingerprinterRegistry, resolver);
            builder.put("parameters." + entry.getKey(), fingerprint);
        }
        ArtifactTransformAction artifactTransformAction = newTransformAction(primaryInput, dependencies);
        propertyWalker.visitProperties(artifactTransformAction, ParameterValidationContext.NOOP, new PropertyVisitor() {
            @Override
            public boolean visitOutputFilePropertiesOnly() {
                return false;
            }

            @Override
            public void visitInputFileProperty(String propertyName, boolean optional, boolean skipWhenEmpty, Class<? extends FileNormalizer> fileNormalizer, PropertyValue value, InputFilePropertyType filePropertyType) {
                builder.put("action." + propertyName, fingerprintInput(value, fileNormalizer, filePropertyType, fileCollectionFingerprinterRegistry, resolver));
            }

            @Override
            public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
            }

            @Override
            public void visitOutputFileProperty(String propertyName, boolean optional, PropertyValue value, OutputFilePropertyType filePropertyType) {
            }

            @Override
            public void visitDestroyableProperty(Object value) {
            }

            @Override
            public void visitLocalStateProperty(Object value) {
            }
        });
        return builder.build();
    }

    private ArtifactTransformAction newTransformAction(File inputFile, ArtifactTransformDependencies artifactTransformDependencies) {
        ServiceLookup services = new TransformServiceLookup(inputFile, parameterObject.isolate(), requiresDependencies ? artifactTransformDependencies : null);
        return instanceFactory.newInstance(services);
    }

    private static class TransformServiceLookup implements ServiceLookup {
        private final ImmutableList<InjectionPoint> injectionPoints;

        public TransformServiceLookup(File inputFile, @Nullable Object parameters, @Nullable ArtifactTransformDependencies artifactTransformDependencies) {
            ImmutableList.Builder<InjectionPoint> builder = ImmutableList.builder();
            builder.add(new InjectionPoint(PrimaryInput.class, File.class, inputFile));
            if (parameters != null) {
                builder.add(new InjectionPoint(TransformParameters.class, parameters.getClass(), parameters));
            }
            if (artifactTransformDependencies != null) {
                builder.add(new InjectionPoint(PrimaryInputDependencies.class, artifactTransformDependencies.getFiles()));
            }
            this.injectionPoints = builder.build();
        }

        @Nullable
        private
        Object find(Type serviceType, @Nullable Class<? extends Annotation> annotatedWith) {
            TypeToken<?> serviceTypeToken = TypeToken.of(serviceType);
            for (InjectionPoint injectionPoint : injectionPoints) {
                if (annotatedWith == injectionPoint.getAnnotation() && serviceTypeToken.isSupertypeOf(injectionPoint.getInjectedType())) {
                    return injectionPoint.getValueToInject();
                }
            }
            return null;
        }

        @Nullable
        @Override
        public Object find(Type serviceType) throws ServiceLookupException {
            return find(serviceType, null);
        }

        @Override
        public Object get(Type serviceType) throws UnknownServiceException, ServiceLookupException {
            Object result = find(serviceType);
            if (result == null) {
                throw new UnknownServiceException(serviceType, "No service of type " + serviceType + " available.");
            }
            return result;
        }

        @Override
        public Object get(Type serviceType, Class<? extends Annotation> annotatedWith) throws UnknownServiceException, ServiceLookupException {
            Object result = find(serviceType, annotatedWith);
            if (result == null) {
                throw new UnknownServiceException(serviceType, "No service of type " + serviceType + " available.");
            }
            return result;
        }

        private static class InjectionPoint {
            private final Class<? extends Annotation> annotation;
            private final Class<?> injectedType;
            private final Object valueToInject;

            public InjectionPoint(Class<? extends Annotation> annotation, Class<?> injectedType, Object valueToInject) {
                this.annotation = annotation;
                this.injectedType = injectedType;
                this.valueToInject = valueToInject;
            }

            public InjectionPoint(Class<? extends Annotation> annotation, Object valueToInject) {
                this(annotation, determineTypeFromAnnotation(annotation), valueToInject);
            }

            private static Class<?> determineTypeFromAnnotation(Class<? extends Annotation> annotation) {
                Class<?>[] supportedTypes = annotation.getAnnotation(InjectionPointQualifier.class).supportedTypes();
                if (supportedTypes.length != 1) {
                    throw new IllegalArgumentException("Cannot determine supported type for annotation " + annotation.getName());
                }
                return supportedTypes[0];
            }

            public Class<? extends Annotation> getAnnotation() {
                return annotation;
            }

            public Class<?> getInjectedType() {
                return injectedType;
            }

            public Object getValueToInject() {
                return valueToInject;
            }
        }
    }
}
