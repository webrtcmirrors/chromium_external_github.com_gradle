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

package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.api.internal.tasks.properties.PropertyWalker;
import org.gradle.internal.file.PathToFileResolver;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResolveTaskPropertiesExecuter implements TaskExecuter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveTaskPropertiesExecuter.class);

    private final PropertyWalker propertyWalker;
    private final PathToFileResolver fileResolver;
    private final TaskExecuter executer;

    public ResolveTaskPropertiesExecuter(PathToFileResolver fileResolver, PropertyWalker propertyWalker, TaskExecuter executer) {
        this.propertyWalker = propertyWalker;
        this.fileResolver = fileResolver;
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task, TaskStateInternal state, final TaskExecutionContext context) {
        Timer clock = Time.startTimer();
        TaskProperties taskProperties = DefaultTaskProperties.resolve(propertyWalker, fileResolver, task);
        context.setTaskProperties(taskProperties);
        return executer.execute(task, state, context);
    }
}
