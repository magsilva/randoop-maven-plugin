package randoop.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author sbt-kuleshov-pi
 * created on 03.05.2018
 */

public class MavenProcessThread implements Runnable {
    private static final Log LOGGER = new SystemStreamLog();
    private List<String> args;
    private Class<?> clazz;
    private File baseDir;
    private int timeoutInSeconds;
    private CountDownLatch latch;
    private String className;
    private String prefix;

    MavenProcessThread(CountDownLatch latch, List<String> args, Class<?> clazz, File baseDir, int timeoutInSeconds) {
        this.latch = latch;
        this.args = args;
        this.clazz = clazz;
        this.baseDir = baseDir;
        this.timeoutInSeconds = timeoutInSeconds;
        className = clazz.getName();
        String prefixName = clazz.getSimpleName().equals("") ? className : clazz.getSimpleName();
        prefix = "[" + prefixName + "]";
    }


    @Override
    public void run() {
        LOGGER.info(prefix + "STARTS");
        List<String> fileFullArgs = new ArrayList<>(args);
        fileFullArgs.addAll(getClassParameters(clazz));
        final String randoopCommandLine = fileFullArgs.stream().collect(Collectors.joining(" "));
        LOGGER.info(prefix + "Call outside Maven: " + randoopCommandLine);
        runRandoopGenTests(fileFullArgs);
        latch.countDown();
    }

    private List<String> getClassParameters(Class currentClass) {
        List<String> classArgs = new ArrayList<>(2);
        String targetClassName = currentClass.getSimpleName() + "RandoopTest";
        classArgs.add("--testclass=" + className);
        classArgs.add("--regression-test-basename=" + targetClassName);
        classArgs.add("--junit-package-name=" + clazz.getPackage().getName());
        return classArgs;
    }


    private void runRandoopGenTests(final List<String> args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        processBuilder.directory(baseDir);
        try {
            Process randoopProcess = processBuilder.start();
            LOGGER.info(prefix + "Randoop started with time limit of " + timeoutInSeconds + " seconds.");
            randoopProcess.waitFor(timeoutInSeconds + 10L, TimeUnit.SECONDS);
            if (randoopProcess.exitValue() != 0) {
                throw new MojoFailureException(this, "Randoop encountered an error!",
                        "Failed to generate test, exit value is " + randoopProcess.exitValue());
            }
        } catch (Exception e) {
            LOGGER.error(prefix + "randoop process problem : " + e.getMessage());
        }
        LOGGER.info(prefix + "Thread completed");

    }


}
