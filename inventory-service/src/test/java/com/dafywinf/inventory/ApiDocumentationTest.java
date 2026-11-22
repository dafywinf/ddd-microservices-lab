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
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Inventory Management")
@Feature("API Documentation")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocumentationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    static final GenericContainer<?> MONGO = new GenericContainer<>(TestImages.MONGO)
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static final RedpandaContainer KAFKA = new RedpandaContainer(TestImages.REDPANDA);

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
    @Story("OpenAPI Document Published")
    @Description("Verifies that /v3/api-docs returns an OpenAPI 3 document titled 'Inventory Service'.")
    void openApiDocumentIsPublished() {
        var doc = fetchApiDocs();
        assertThat(doc.path("openapi").asString()).startsWith("3.");
        assertThat(doc.path("info").path("title").asString()).isEqualTo("Inventory Service");
        assertThat(doc.path("info").path("version").asString()).isNotBlank();
    }

    @Test
    @Story("REST Endpoints Documented")
    @Description("Verifies that every endpoint of the Inventory controller appears in the OpenAPI document.")
    void restEndpointsAreDocumented() {
        var paths = fetchApiDocs().path("paths");
        for (var path : new String[] {"/inventory/seed"}) {
            assertThat(paths.has(path))
                    .as("Expected path '%s' in /v3/api-docs", path)
                    .isTrue();
        }
    }

    @Test
    @Story("Swagger UI Available")
    @Description("Verifies that /swagger-ui.html leads to the Swagger UI page.")
    void swaggerUiIsServed() {
        var response = get("/swagger-ui.html");
        assertThat(response.body()).contains("Swagger UI");
    }

    // --- Allure Step Helpers ---

    @Step("GET /v3/api-docs and parse the OpenAPI document")
    private JsonNode fetchApiDocs() {
        return MAPPER.readTree(get("/v3/api-docs").body());
    }

    @Step("GET {path} and assert HTTP 200")
    private HttpResponse<String> get(String path) {
        var uri = URI.create("http://localhost:" + port + path);
        HttpResponse<String> response;
        try {
            response = HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError("HTTP call to " + uri + " failed: " + e.getMessage(), e);
        }
        assertThat(response.statusCode())
                .as("Expected HTTP 200 from %s", uri)
                .isEqualTo(HttpStatus.OK.value());
        return response;
    }
}
