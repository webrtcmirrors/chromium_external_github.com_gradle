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
    private static String perfRecordPeriod = System.getProperty("perfRecordPeriod");
    private static String perfAgentDir = "/root/perf-map-agent";
    private static String flameGraphDir = "/root/FlameGraph";
    private static ExecutorService threadPool = Executors.newSingleThreadExecutor();

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

        threadPool.shutdown();
    }

    private static ExperimentSet runASetOfExperiments() {
        String[] versions = System.getProperty("expVersions").split(",");

        Stream.of(versions).forEach(Main::prepareForExperiment);

        Future perfFuture = perfRecordIfNecessary();

        List<Experiment> results = Stream.of(versions).map(Main::runExperiment).collect(Collectors.toList());

        processPerfRecordIfNecessary(perfFuture);

        ExperimentSet comparison = new ExperimentSet(results.toArray(new Experiment[0]));

        comparison.printResultsAndConfidence();

//        if (comparison.confidence > 0.99) {
//            throw new IllegalStateException("We need to stop.");
//        }
        return comparison;
    }

    private static void prepareForExperiment(String version) {
        initDirectory(getGradleUserHome(version));
        deleteDirectory(getExpProject(version));

        run(projectDir, "cp", "-r",
            projectDirPath + "/subprojects/performance/build/largeJavaMultiProjectKotlinDsl",
            getExpProject(version).getAbsolutePath());
    }

    private static void stopDaemon(String version) {
        run(getExpProject(version), getExpArgs(version, "--stop"));
    }

    private static Experiment runExperiment(String version) {
        doWarmUp(version);

        List<Long> results = doRun(version, getExpArgs(version, "help"));

        stopDaemon(version);
        return new Experiment(version, results);
    }

    private static void processPerfRecordIfNecessary(Future perfFuture) {
        if (perfFuture != null) {
            try {
                perfFuture.get();
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                String flameGraphFileName = timestamp + "-flamegraph.svg";
                String stackFileName = timestamp + ".stack";
                run(projectDir, "bash", "-c", "perf script | " + flameGraphDir + "/stackcollapse-perf.pl | " + flameGraphDir + "/flamegraph.pl --color=java --hash > " + flameGraphFileName);
                run(projectDir, "bash", "-c", "perf script --header > " + stackFileName);
                run(projectDir, "gzip", stackFileName);
            } catch (Exception e) {
                handleException(e);
            }
        }
    }

    private static Future perfRecordIfNecessary() {
        if (perfEnabled()) {
            return threadPool.submit(() -> run(projectDir, "perf", "record", "-F", "100", "-a", "-g", "--", "sleep", perfRecordPeriod));
        } else {
            return null;
        }
    }

    private static void attachToDaemon(String version) {
        if (perfEnabled()) {
            String pid = readFile(getPidFile(version));
            run(new File(perfAgentDir, "out"), javaHome + "/bin/java", "-cp", "attach-main.jar:" + javaHome + "/lib/tools.jar", "net.virtualvoid.perf.AttachOnce", pid);
        }
    }

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            handleException(e);
            return null;
        }
    }

    private static void writeFile(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(), StandardOpenOption.WRITE);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static List<Long> doRun(String version, List<String> args) {

        return IntStream.range(0, runCount).mapToObj(i -> measureOnce(i, version, args)).collect(Collectors.toList());
    }

    private static long measureOnce(int index, String version, List<String> args) {
        String pid = jfrEnabled() ? readFile(getPidFile(version)) : null;
        File workingDir = getExpProject(version);

        if (jfrEnabled()) {
            run(workingDir, jcmdPath, pid, "JFR.start", "name=" + version + "_" + index, "settings=" + jfcPath);
        }

        long t0 = System.currentTimeMillis();
        run(workingDir, args);
        long result = System.currentTimeMillis() - t0;

        if (jfrEnabled()) {
            run(workingDir, jcmdPath, pid, "JFR.stop", "name=" + version + "_" + index, "filename=" + getJfrPath(version, index));
        }

        return result;
    }

    private static boolean jfrEnabled() {
        return Boolean.parseBoolean(System.getProperty("jfrEnabled"));
    }

    private static boolean perfEnabled() {
        return Boolean.parseBoolean(System.getProperty("perfEnabled"));
    }

    private static String getJfrPath(String version, int iteration) {
        return new File(getExpProject(version), version + "_" + iteration + ".jfr").getAbsolutePath();
    }

    private static List<String> getExpArgs(String version, String task) {
        String jvmArgs = jfrEnabled()
            ? "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:FlightRecorderOptions=stackdepth=1024 -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PreserveFramePointer"
            : "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m -XX:+PreserveFramePointer";

        return Arrays.asList(
            gradleBinary.get(version),
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            jvmArgs,
            task
        );
    }

    private static List<String> getWarmupExpArgs(String version, String task) {
        List<String> args = new ArrayList<>(getExpArgs(version, task));
        args.add("--init-script");
        args.add(projectDirPath + "/pid-instrumentation.gradle");
        return args;
    }

    private static File getPidFile(String version) {
        return new File(getExpProject(version), "pid");
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static void doWarmUp(String version) {
        File workingDir = getExpProject(version);
        int warmups = Integer.parseInt(System.getProperty("warmUp"));

        Map<String, String> env = new HashMap<>();
        env.put("PID_FILE_PATH", getPidFile(version).getAbsolutePath());

        // first warmup to write pid
        run(workingDir, env, getWarmupExpArgs(version, "help"));

        attachToDaemon(version);

        IntStream.range(1, warmups).forEach(i -> {
            run(workingDir, getExpArgs(version, "help"));
        });
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
