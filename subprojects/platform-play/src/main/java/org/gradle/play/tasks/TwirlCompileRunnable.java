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

package org.gradle.play.tasks;

import org.gradle.api.UncheckedIOException;
import org.gradle.play.internal.twirl.TwirlCompileSpec;
import org.gradle.play.internal.twirl.TwirlCompiler;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Function;

public class TwirlCompileRunnable implements Runnable {
    private final TwirlCompileSpec twirlCompileSpec;
    private final TwirlCompiler twirlCompiler;

    @Inject
    public TwirlCompileRunnable(TwirlCompileSpec twirlCompileSpec, TwirlCompiler twirlCompiler) {
        this.twirlCompileSpec = twirlCompileSpec;
        this.twirlCompiler = twirlCompiler;
    }

    @Override
    public void run() {
        Path destinationPath = twirlCompileSpec.getDestinationDir().toPath();
        deleteOutputs(destinationPath);
        twirlCompiler.execute(twirlCompileSpec);
    }

    private void deleteOutputs(Path pathToBeDeleted) {

        try {
            Files.walk(pathToBeDeleted).map(new Function<Path, File>() {
                @Override
                public File apply(Path path) {
                    return path.toFile();
                }
            }).forEach(new Consumer<File>() {
                @Override
                public void accept(File file) {
                    file.delete();
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
