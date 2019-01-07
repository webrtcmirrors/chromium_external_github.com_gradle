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

import org.apache.commons.math3.stat.inference.MannWhitneyUTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.text.*;
import java.util.stream.*;

public class Main {
    private static String projectDirPath = "/home/tcagent1/agent/work/668602365d1521fc";
    private static File projectDir = new File(projectDirPath);
    private static Map<String, String> gradleBinary = new HashMap<>();
    private static String javaHome = System.getenv("JAVA_HOME");
    private static String jcmdPath = javaHome + "/bin/jcmd";
    private static String jfcPath = projectDirPath + "/subprojects/internal-performance-testing/src/main/resources/org/gradle/performance/fixture/gradle.jfc";
    private static int runCount = Integer.parseInt(System.getProperty("runCount"));
    private static int warmups = Integer.parseInt(System.getProperty("warmUp"));

    static {
        gradleBinary.put("baseline1",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000/bin/gradle");
        gradleBinary.put("baseline2",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-2/bin/gradle");
        gradleBinary.put("baseline3",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-3/bin/gradle");
        gradleBinary.put("baseline4",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-4/bin/gradle");
        gradleBinary.put("current1",
            projectDirPath + "/subprojects/performance/build/integ test/bin/gradle");
        gradleBinary.put("current2",
            projectDirPath + "/subprojects/performance/build/integ test-2/bin/gradle");
    }

    private static class Experiment {
        String version;
        List<Long> results;

        public Experiment(String version, List<Long> results) {
            this.version = version;
            this.results = results;
        }

        public String getVersion() {
            return version;
        }

        private double[] toDoubleArray() {
            return results.stream().mapToDouble(Long::doubleValue).toArray();
        }

        public String toString() {
            return version + ": " + results.stream().map(s -> s + " ms").collect(Collectors.joining(", "));
        }
    }

    private static class ExperimentSet {
        Experiment[] versions;

        public ExperimentSet(Experiment[] versions) {
            this.versions = versions;
        }

        public void printResultsAndConfidence() {
            System.out.println(toString());

            String fileName = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-result.txt";
            run(projectDir, "touch", fileName);
            writeFile(new File(fileName), toString());
        }

        public String toString() {
            String versionString = Stream.of(versions).map(Experiment::toString).collect(Collectors.joining("\n")) + "\n";

            for (int i = 0; i < versions.length; ++i) {
                for (int j = i + 1; j < versions.length; ++j) {
                    versionString += String.format("Confidence of %s and %s: %f\n", versions[i].version, versions[j].version, 1 - new MannWhitneyUTest().mannWhitneyUTest(versions[i].toDoubleArray(), versions[j].toDoubleArray()));
                }
            }
            return versionString;
        }
    }

    public static void main(String[] args) {
        List<ExperimentSet> allResults = IntStream.range(0, Integer.parseInt(System.getProperty("retryCount"))).mapToObj(i -> runASetOfExperiments()).collect(Collectors.toList());

        if (allResults.size() > 1) {
            System.out.println("All results:");
            allResults.forEach(ExperimentSet::printResultsAndConfidence);
        }
    }

    private static ExperimentSet runASetOfExperiments() {
        String[] versions = System.getProperty("expVersions").split(",");

        Stream.of(versions).forEach(Main::prepareForExperiment);

        runInterleaveWarmups(versions);

        List<Experiment> results = runInterleaveExperiments(versions);

        ExperimentSet comparison = new ExperimentSet(results.toArray(new Experiment[0]));

        comparison.printResultsAndConfidence();

        return comparison;
    }

    private static void prepareForExperiment(String version) {
        initDirectory(getGradleUserHome(version));
        deleteDirectory(getExpProject(version));

        run(projectDir, "cp", "-r",
            projectDirPath + "/subprojects/performance/build/largeJavaMultiProjectKotlinDsl",
            getExpProject(version).getAbsolutePath());
    }

    private static void runInterleaveWarmups(String[] versions) {
        for (int i = 0; i < warmups; ++i) {
            for (int j = 0; j < versions.length; ++j) {
                System.out.println("Running " + versions[j] + " warmup " + i);
                doWarmUp(versions[j]);
            }
        }
    }

    private static void doWarmUp(String version) {
        run(getExpProject(version), getExpArgs(version, "help"));
    }

    private static List<Experiment> runInterleaveExperiments(String[] versions) {
        List<List<Long>> times = new ArrayList<>();
        for (int versionIndex = 0; versionIndex < versions.length; ++versionIndex) {
            times.add(new ArrayList<>());
        }
        for (int i = 0; i < runCount; i++) {
            for (int j = 0; j < versions.length; j++) {
                System.out.println("Running " + versions[j] + " execution " + i);
                long time = measureOnce(versions[j]);
                times.get(j).add(time);
            }
        }

        List<Experiment> experiments = new ArrayList<>();
        for (int versionIndex = 0; versionIndex < versions.length; ++versionIndex) {
            experiments.add(new Experiment(versions[versionIndex], times.get(versionIndex)));
            stopDaemon(versions[versionIndex]);
        }

        return experiments;
    }

    private static void stopDaemon(String version) {
        run(getExpProject(version), getExpArgs(version, "--stop"));
    }

    private static void writeFile(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(), StandardOpenOption.WRITE);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static long measureOnce(String version) {
        File workingDir = getExpProject(version);
        List<String> args = getExpArgs(version, "help");

        long t0 = System.currentTimeMillis();
        run(workingDir, args);
        return System.currentTimeMillis() - t0;
    }

    private static List<String> getExpArgs(String version, String task) {
        String jvmArgs = "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m";

        return Arrays.asList(
            gradleBinary.get(version),
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            jvmArgs,
            task
        );
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            run(projectDir, "rm", "-rf", dir.getAbsolutePath());
        }
    }

    private static void initDirectory(File dir) {
        deleteDirectory(dir);
        assertTrue(dir.mkdir());
    }

    private static void handleException(Exception e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else {
            throw new RuntimeException(e);
        }
    }

    private static void run(File workingDir, List<String> args) {
        run(workingDir, null, args);
    }

    private static void run(File workingDir, Map<String, String> envs, List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args).directory(workingDir).inheritIO();
            if (envs != null) {
                pb.environment().putAll(envs);
            }
            int code = pb.start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void run(File workingDir, String... args) {
        run(workingDir, Arrays.asList(args));
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new IllegalStateException();
        }
    }
}
