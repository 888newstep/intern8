package vip.xiaozhao.intern.baseUtil.integration;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class ContainerTestSupport {

    private ContainerTestSupport() {
    }

    static void assumeDockerAvailable() {
        boolean required = Boolean.parseBoolean(
                System.getProperty("integration.requireDocker", "false"));
        if (!dockerCommandAndDaemonAvailable()) {
            failOrSkip(required, "Docker CLI or daemon is unavailable", null);
            return;
        }
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable exception) {
            failOrSkip(required, "Docker availability check failed: "
                    + exception.getClass().getSimpleName(), exception);
            return;
        }
        if (!available) {
            failOrSkip(required, "Docker is unavailable", null);
        }
    }

    private static boolean dockerCommandAndDaemonAvailable() {
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void failOrSkip(boolean required, String message, Throwable cause) {
        if (required) {
            throw new IllegalStateException(message, cause);
        }
        Assumptions.assumeTrue(false, message + "; integration test skipped");
    }
}
