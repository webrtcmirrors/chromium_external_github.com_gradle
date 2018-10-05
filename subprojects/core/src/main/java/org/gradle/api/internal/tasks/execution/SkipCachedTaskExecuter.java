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

package org.gradle.api.internal.tasks.execution;

import com.google.common.collect.ImmutableSortedSet;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.changedetection.TaskArtifactState;
import org.gradle.api.internal.tasks.CacheableTaskOutputFilePropertySpec;
import org.gradle.api.internal.tasks.OriginTaskExecutionMetadata;
import org.gradle.api.internal.tasks.ResolvedTaskOutputFilePropertySpec;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskExecutionOutcome;
import org.gradle.api.internal.tasks.TaskOutputFilePropertySpec;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.caching.internal.controller.BuildCacheController;
import org.gradle.caching.internal.tasks.TaskOutputCacheCommandFactory;
import org.gradle.caching.internal.tasks.TaskOutputCachingBuildCacheKey;
import org.gradle.caching.internal.tasks.UnrecoverableTaskOutputUnpackingException;
import org.gradle.internal.Factory;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.work.WorkerLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.SortedSet;

public class SkipCachedTaskExecuter implements TaskExecuter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkipCachedTaskExecuter.class);

    private final BuildCacheController buildCache;
    private final TaskExecuter delegate;
    private final TaskOutputChangesListener taskOutputChangesListener;
    private final TaskOutputCacheCommandFactory buildCacheCommandFactory;
    private final WorkerLeaseService workerLeaseService;

    public SkipCachedTaskExecuter(
        BuildCacheController buildCache,
        TaskOutputChangesListener taskOutputChangesListener,
        TaskOutputCacheCommandFactory buildCacheCommandFactory,
        WorkerLeaseService workerLeaseService, TaskExecuter delegate
    ) {
        this.taskOutputChangesListener = taskOutputChangesListener;
        this.buildCacheCommandFactory = buildCacheCommandFactory;
        this.buildCache = buildCache;
        this.workerLeaseService = workerLeaseService;
        this.delegate = delegate;
    }

    @Override
    public void execute(final TaskInternal task, TaskStateInternal state, final TaskExecutionContext context) {
        LOGGER.debug("Determining if {} is cached already", task);

        final TaskProperties taskProperties = context.getTaskProperties();
        final TaskOutputCachingBuildCacheKey cacheKey = context.getBuildCacheKey();
        boolean taskOutputCachingEnabled = state.getTaskOutputCaching().isEnabled();

        final SortedSet<ResolvedTaskOutputFilePropertySpec> outputProperties;

        if (taskOutputCachingEnabled) {
            if (task.isHasCustomActions()) {
                LOGGER.info("Custom actions are attached to {}.", task);
            }
            final TaskArtifactState taskState = context.getTaskArtifactState();
            // TODO: This is really something we should do at an earlier/higher level so that the input and output
            // property values are locked in at this point.
            outputProperties = resolveProperties(taskProperties.getOutputFileProperties());
            if (taskState.isAllowedToUseCachedResults()) {
                try {
                    OriginTaskExecutionMetadata originMetadata = workerLeaseService.withoutProjectLock(new Factory<OriginTaskExecutionMetadata>() {
                        @Nullable
                        @Override
                        public OriginTaskExecutionMetadata create() {
                            return buildCache.load(buildCacheCommandFactory.createLoad(cacheKey, outputProperties, task, taskProperties, taskOutputChangesListener, taskState));
                        }
                    });
                    if (originMetadata != null) {
                        state.setOutcome(TaskExecutionOutcome.FROM_CACHE);
                        context.setOriginExecutionMetadata(originMetadata);
                        return;
                    }
                } catch (UnrecoverableTaskOutputUnpackingException e) {
                    // We didn't manage to recover from the unpacking error, there might be leftover
                    // garbage among the task's outputs, thus we must fail the build
                    throw e;
                } catch (Exception e) {
                    // There was a failure during downloading, previous task outputs should bu unaffected
                    LOGGER.warn("Failed to load cache entry for {}, falling back to executing task", task, e);
                }
            } else {
                LOGGER.info("Not loading {} from cache because loading from cache is disabled for this task", task);
            }
        } else {
            outputProperties = null;
        }

        delegate.execute(task, state, context);

        if (taskOutputCachingEnabled) {
            if (state.getFailure() == null) {
                try {
                    TaskArtifactState taskState = context.getTaskArtifactState();
                    final Map<String, CurrentFileCollectionFingerprint> outputFingerprints = taskState.getOutputFingerprints();
                    workerLeaseService.withoutProjectLock(new Runnable() {
                        @Override
                        public void run() {
                            buildCache.store(buildCacheCommandFactory.createStore(cacheKey, outputProperties, outputFingerprints, task, context.getExecutionTime()));
                        }
                    });
                } catch (Exception e) {
                    LOGGER.warn("Failed to store cache entry {}", cacheKey.getDisplayName(), e);
                }
            } else {
                LOGGER.debug("Not storing result of {} in cache because the task failed", task);
            }
        }
    }

    private static SortedSet<ResolvedTaskOutputFilePropertySpec> resolveProperties(ImmutableSortedSet<? extends TaskOutputFilePropertySpec> properties) {
        ImmutableSortedSet.Builder<ResolvedTaskOutputFilePropertySpec> builder = ImmutableSortedSet.naturalOrder();
        for (TaskOutputFilePropertySpec property : properties) {
            // At this point we assume that the task only has cacheable properties,
            // otherwise caching would have been disabled by now
            CacheableTaskOutputFilePropertySpec cacheableProperty = (CacheableTaskOutputFilePropertySpec) property;
            builder.add(new ResolvedTaskOutputFilePropertySpec(
                cacheableProperty.getPropertyName(),
                cacheableProperty.getOutputType(),
                cacheableProperty.getOutputFile()
            ));
        }
        return builder.build();
    }
}
