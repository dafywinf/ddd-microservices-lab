package com.dafywinf.order;

import org.testcontainers.utility.DockerImageName;

/**
 * Container images for the tests: the same versions as platform/platform.yml, so tests run against what the
 * platform runs. Renovate updates both.
 */
final class TestImages {

    static final DockerImageName MONGO    = DockerImageName.parse("mongo:9.0.8");
    static final DockerImageName REDPANDA = DockerImageName.parse("redpandadata/redpanda:v26.3.1");

    private TestImages() {
    }
}
