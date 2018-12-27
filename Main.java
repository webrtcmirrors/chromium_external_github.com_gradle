import org.apache.commons.math3.stat.inference.MannWhitneyUTest;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {
    private static String projectDirPath = "/home/tcagent1/agent/work/668602365d1521fc";
    private static File projectDir = new File(projectDirPath);
    private static Map<String, String> gradleBinary = new HashMap<>();

    static {
        gradleBinary.put("baseline",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000/bin/gradle");
        gradleBinary.put("current",
            projectDirPath + "/subprojects/performance/build/integ test/bin/gradle");
    }

    public static void main(String[] args) {
        List<Long> currentResults = runExperiment("current");
        List<Long> baselineResults = runExperiment("baseline");

        System.out.println("Current: " + currentResults.stream().map(s -> s + " ms").collect(Collectors.joining(", ")));
        System.out.println("Baseline: " + baselineResults.stream().map(s -> s + " ms").collect(Collectors.joining(", ")));

        System.out.println("Confidence: " + (1 - new MannWhitneyUTest().mannWhitneyUTest(toDoubleArray(currentResults), toDoubleArray(baselineResults))));
    }

    private static double[] toDoubleArray(List<Long> results) {
        return results.stream().mapToDouble(Long::doubleValue).toArray();
    }

    private static List<Long> runExperiment(String version) {
        initDirectory(getGradleUserHome(version));
        deleteDirectory(getExpProject(version));

        run(projectDir, "cp", "-r",
            projectDirPath + "/subprojects/performance/build/largeJavaMultiProjectKotlinDsl",
            getExpProject(version).getAbsolutePath());

        List<String> args = getExpArgs(version);
        doWarmUp(getExpProject(version), args);
        return doRun(getExpProject(version), args);
    }

    private static List<Long> doRun(File workdingDir, List<String> args) {
        int runCount = Integer.parseInt(System.getProperty("runExperiment"));
        return IntStream.range(0, runCount).mapToObj(i -> measureOnce(workdingDir, args)).collect(Collectors.toList());
    }

    private static long measureOnce(File workingDir, List<String> args) {
        long t0 = System.currentTimeMillis();
        run(workingDir, args);
        return System.currentTimeMillis() - t0;
    }

    private static List<String> getExpArgs(String version) {
        return Arrays.asList(
            gradleBinary.get(version),
            "help",
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m"
        );
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static void doWarmUp(File workingDir, List<String> args) {
        int warmups = Integer.parseInt(System.getProperty("warmUp"));
        IntStream.range(0, warmups).forEach(i -> run(workingDir, args));
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
        try {
            int code = new ProcessBuilder(args).directory(workingDir).inheritIO().start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void run(File workingDir, String... args) {
        try {
            int code = new ProcessBuilder(args).directory(workingDir).inheritIO().start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new IllegalStateException();
        }
    }
}
