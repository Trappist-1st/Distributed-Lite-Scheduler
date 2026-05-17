package com.imperium.distributed_lite_scheduler_v1.service.executor;

import com.imperium.distributed_lite_scheduler_v1.model.entity.ResourceNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerEndpointResolverTest {

    private final WorkerEndpointResolver resolver = new WorkerEndpointResolver();

    @Test
    void prefersExplicitWorkerEndpoint() {
        ResourceNode node = new ResourceNode();
        node.setWorkerEndpoint("http://worker.example.com:9090/");
        node.setNodeHost("10.0.0.1");
        node.setNodePort(8080);
        assertEquals("http://worker.example.com:9090", resolver.resolveBaseUrl(node));
    }

    @Test
    void fallsBackToHostPort() {
        ResourceNode node = new ResourceNode();
        node.setNodeHost("192.168.1.10");
        node.setNodePort(9090);
        assertEquals("http://192.168.1.10:9090", resolver.resolveBaseUrl(node));
    }
}
