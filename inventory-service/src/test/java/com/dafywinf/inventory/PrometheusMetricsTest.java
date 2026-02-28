package com.dafywinf.inventory;

import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Epic("Observability")
@Feature("Prometheus Metrics")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PrometheusMetricsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:7.0")
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

    static {
        var mongoThread = new Thread(MONGO::start, "start-mongo");
        var kafkaThread = new Thread(KAFKA::start, "start-kafka");
        mongoThread.start();
        kafkaThread.start();
        try {
            mongoThread.join();
            kafkaThread.join();
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500);
        } catch (Exception e) {
            throw new RuntimeException("Container startup failed", e);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () ->
                "mongodb://localhost:" + MONGO.getMappedPort(27017)
                        + "/inventory_db?directConnection=true&replicaSet=rs0");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    @Story("Prometheus Endpoint Availability")
    @Description("Verifies that /actuator/prometheus returns HTTP 200 with a non-empty body.")
    void prometheusEndpointIsReachableAndReturns200() {
        var body = fetchPrometheusBody();
        assertThat(body).isNotBlank();
    }

    @Test
    @Story("JVM Metrics Exposed")
    @Description("Verifies that standard JVM memory, thread, and class-loading metrics are present in the scrape output.")
    void prometheusEndpointExposesJvmMetrics() {
        var body = fetchPrometheusBody();
        assertMetricPresent(body, "jvm_memory_used_bytes");
        assertMetricPresent(body, "jvm_memory_max_bytes");
        assertMetricPresent(body, "jvm_threads_live_threads");
        assertMetricPresent(body, "jvm_classes_loaded_classes");
    }

    @Test
    @Story("Process Metrics Exposed")
    @Description("Verifies that process uptime and CPU usage metrics are present in the Prometheus scrape output.")
    void prometheusEndpointExposesProcessMetrics() {
        var body = fetchPrometheusBody();
        assertMetricPresent(body, "process_uptime_seconds");
        assertMetricPresent(body, "process_cpu_usage");
    }

    @Test
    @Story("System Metrics Exposed")
    @Description("Verifies that system-level CPU usage is present in the Prometheus scrape output.")
    void prometheusEndpointExposesSystemMetrics() {
        var body = fetchPrometheusBody();
        assertMetricPresent(body, "system_cpu_usage");
    }

    @Test
    @Story("Application Tag Applied")
    @Description("Verifies that Prometheus metrics carry the 'application' tag set to 'inventory-service', as configured in application.yml.")
    void prometheusMetricsIncludeApplicationTag() {
        var body = fetchPrometheusBody();
        assertThat(body)
                .as("Expected all metrics to be tagged with application=\"inventory-service\"")
                .contains("application=\"inventory-service\"");
    }

    // --- Allure Step Helpers ---

    @Step("GET /actuator/prometheus and assert HTTP 200")
    private String fetchPrometheusBody() {
        var uri = URI.create("http://localhost:" + port + "/actuator/prometheus");
        var request = HttpRequest.newBuilder(uri).GET().build();
        var captured = new AtomicReference<String>();

        await().atMost(10, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    HttpResponse<String> response;
                    try {
                        response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                    } catch (Exception e) {
                        throw new AssertionError("HTTP call to " + uri + " failed: " + e.getMessage(), e);
                    }
                    assertThat(response.statusCode())
                            .as("Expected HTTP 200 from %s", uri)
                            .isEqualTo(HttpStatus.OK.value());
                    assertThat(response.body()).isNotBlank();
                    captured.set(response.body());
                });

        return captured.get();
    }


    @Step("Assert metric '{name}' is present in scrape output")
    private void assertMetricPresent(String body, String name) {
        assertThat(body)
                .as("Expected Prometheus metric '%s' to be present in /actuator/prometheus output", name)
                .contains(name);
    }
}