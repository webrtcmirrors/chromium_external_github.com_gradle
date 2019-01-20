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

package org.gradle.internal.component.local.model;

import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.internal.DisplayName;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.IvyArtifactName;

import java.io.File;

public class PublishArtifactLocalArtifactMetadata implements LocalComponentArtifactMetadata {
    private final ArtifactId id;
    private final PublishArtifact publishArtifact;

    public PublishArtifactLocalArtifactMetadata(ComponentIdentifier componentIdentifier, PublishArtifact publishArtifact) {
        this.id = new ArtifactId(componentIdentifier, DefaultIvyArtifactName.forPublishArtifact(publishArtifact), publishArtifact.getFile());
        this.publishArtifact = publishArtifact;
    }

    @Override
    public String toString() {
        return id.getDisplayName();
    }

    @Override
    public ComponentArtifactIdentifier getId() {
        return id;
    }

    @Override
    public ComponentIdentifier getComponentId() {
        return id.componentIdentifier;
    }

    @Override
    public IvyArtifactName getName() {
        return id.ivyArtifactName;
    }

    @Override
    public File getFile() {
        return id.artifactFile;
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return publishArtifact.getBuildDependencies();
    }

    private static class ArtifactId implements ComponentArtifactIdentifier, DisplayName {
        private final ComponentIdentifier componentIdentifier;
        private final DefaultIvyArtifactName ivyArtifactName;
        private final File artifactFile;

        public ArtifactId(ComponentIdentifier componentIdentifier, DefaultIvyArtifactName ivyArtifactName, File artifactFile) {
            this.componentIdentifier = componentIdentifier;
            this.ivyArtifactName = ivyArtifactName;
            this.artifactFile = artifactFile;
        }

        @Override
        public ComponentIdentifier getComponentIdentifier() {
            return componentIdentifier;
        }

        public String getDisplayName() {
            StringBuilder result = new StringBuilder();
            result.append(ivyArtifactName);
            result.append(" (")
                    .append(componentIdentifier.getDisplayName())
                    .append(")");
            return result.toString();
        }

        @Override
        public String getCapitalizedDisplayName() {
            return getDisplayName();
        }


        @Override
        public int hashCode() {
            return componentIdentifier.hashCode() ^ ivyArtifactName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            ArtifactId other = (ArtifactId) obj;
            return other.componentIdentifier.equals(componentIdentifier)
                    && other.ivyArtifactName.equals(ivyArtifactName)
                    && other.artifactFile.equals(artifactFile);
        }
    }
}
