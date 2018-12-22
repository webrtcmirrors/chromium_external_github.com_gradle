/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.launcher.daemon.registry;

import org.gradle.cache.FileLockManager;
import org.gradle.internal.nativeintegration.filesystem.Chmod;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Takes care of instantiating and wiring together the services required for a daemon registry.
 */
public class DaemonRegistryServices {
    private final File daemonBaseDir;

    private static final ConcurrentMap<File, DaemonRegistry> REGISTRY_STORAGE = new ConcurrentHashMap<File, DaemonRegistry>();

    public DaemonRegistryServices(File daemonBaseDir) {
        this.daemonBaseDir = daemonBaseDir;
    }

    DaemonDir createDaemonDir() {
        return new DaemonDir(daemonBaseDir);
    }

    DaemonRegistry createDaemonRegistry(DaemonDir daemonDir, final FileLockManager fileLockManager, final Chmod chmod) {
        final File daemonRegistryFile = daemonDir.getRegistry();
        return REGISTRY_STORAGE.computeIfAbsent(daemonRegistryFile, new Function<File, DaemonRegistry>() {
            @Override
            public DaemonRegistry apply(File file) {
                return new PersistentDaemonRegistry(daemonRegistryFile, fileLockManager, chmod);
            }
        });
    }

    Properties createProperties() {
        return System.getProperties();
    }
}
