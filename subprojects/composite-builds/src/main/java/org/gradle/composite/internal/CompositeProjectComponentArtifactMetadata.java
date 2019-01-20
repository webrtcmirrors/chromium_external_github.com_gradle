/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.composite.internal;

import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.internal.component.local.model.LocalComponentArtifactMetadata;
import org.gradle.internal.component.model.IvyArtifactName;

import java.io.File;

class CompositeProjectComponentArtifactMetadata implements LocalComponentArtifactMetadata {
    private final ArtifactId id;
    private final LocalComponentArtifactMetadata delegateMetadata;
    private final File file;

    public CompositeProjectComponentArtifactMetadata(ProjectComponentIdentifier componentIdentifier, LocalComponentArtifactMetadata delegateMetadata, File file) {
        this.id = new ArtifactId(componentIdentifier, delegateMetadata.getId());
        this.delegateMetadata = delegateMetadata;
        this.file = file;
    }

    @Override
    public ProjectComponentIdentifier getComponentId() {
        return id.getComponentIdentifier();
    }

    @Override
    public ComponentArtifactIdentifier getId() {
        return id;
    }

    @Override
    public IvyArtifactName getName() {
        return delegateMetadata.getName();
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return delegateMetadata.getBuildDependencies();
    }

    @Override
    public File getFile() {
        return file;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompositeProjectComponentArtifactMetadata)) {
            return false;
        }

        CompositeProjectComponentArtifactMetadata that = (CompositeProjectComponentArtifactMetadata) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private static class ArtifactId implements ComponentArtifactIdentifier, DisplayName {
        private final ProjectComponentIdentifier componentIdentifier;
        private final ComponentArtifactIdentifier delegateId;

        private ArtifactId(ProjectComponentIdentifier componentIdentifier, ComponentArtifactIdentifier delegateId) {
            this.componentIdentifier = componentIdentifier;
            this.delegateId = delegateId;
        }

        @Override
        public ProjectComponentIdentifier getComponentIdentifier() {
            return componentIdentifier;
        }

        @Override
        public String getDisplayName() {
            return delegateId.getDisplayName();
        }

        @Override
        public String getCapitalizedDisplayName() {
            return Describables.of(delegateId).getCapitalizedDisplayName();
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ArtifactId)) {
                return false;
            }

            ArtifactId that = (ArtifactId) o;
            return delegateId.equals(that.delegateId);
        }

        @Override
        public int hashCode() {
            return delegateId.hashCode();
        }
    }
}
