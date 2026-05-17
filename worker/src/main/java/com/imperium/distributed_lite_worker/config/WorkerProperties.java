package com.imperium.distributed_lite_worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    private Api api = new Api();
    private Scheduler scheduler = new Scheduler();
    private Node node = new Node();
    private Executor executor = new Executor();
    private int heartbeatIntervalSeconds = 30;
    private boolean registrationEnabled = true;

    @Data
    public static class Api {
        private String token = "";
    }

    @Data
    public static class Scheduler {
        private String baseUrl = "http://localhost:8080";
    }

    @Data
    public static class Node {
        private String name = "worker-local";
        private String host = "127.0.0.1";
        private int port = 9090;
        private String type = "CPU";
        private int totalCpu = 8;
        private int totalMemoryMb = 16384;
        private int totalGpu = 0;
        private String workerEndpoint = "";
    }

    @Data
    public static class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 200;
        private String workDir = "";
        private int outputMaxChars = 8192;

        public String getWorkDir() {
            if (workDir == null || workDir.isBlank()) {
                return System.getProperty("java.io.tmpdir") + "/dls-worker-work";
            }
            return workDir.trim();
        }
    }
}
