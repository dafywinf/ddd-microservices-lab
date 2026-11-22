package com.dafywinf.order;

import com.dafywinf.order.app.OrderApplicationService;
import com.dafywinf.order.app.OutboxPublisher;
import com.dafywinf.order.domain.OrderRepository;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Order Management")
@Feature("Order Queries")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"orders.v1", "inventory.v1"})
class ListOrdersTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final GenericContainer<?> MONGO = new GenericContainer<>(TestImages.MONGO)
            .withExposedPorts(27017)
            .withCommand("mongod --replSet rs0 --bind_ip_all")
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));

    static {
        MONGO.start();
        try {
            MONGO.execInContainer("mongosh", "--eval",
                    "rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})");
            Thread.sleep(1_500);
        } catch (Exception e) {
            throw new RuntimeException("MongoDB replica-set initialisation failed", e);
        }
    }

    @DynamicPropertySource
    static void mongoUri(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", () ->
                "mongodb://localhost:" + MONGO.getMappedPort(27017)
                        + "/orders?directConnection=true&replicaSet=rs0");
    }

    @MockitoBean
    OutboxPublisher outboxPublisher;

    @LocalServerPort
    int port;

    @Autowired OrderApplicationService service;
    @Autowired OrderRepository         orderRepo;

    @BeforeEach
    void resetDatabase() {
        orderRepo.deleteAll();
    }

    @Test
    @Story("Paging Through Orders")
    @Description("Creates 25 orders and reads them 10 at a time: 3 pages of 10, 10 and 5, newest first, each order exactly once.")
    void pagesThroughAllOrdersNewestFirst() throws InterruptedException {
        var created = createOrders(25);

        var seen = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var body = getJson("/orders?page=" + page + "&size=10");
            assertThat(body.path("page").asInt()).isEqualTo(page);
            assertThat(body.path("size").asInt()).isEqualTo(10);
            assertThat(body.path("totalElements").asLong()).isEqualTo(25);
            assertThat(body.path("totalPages").asInt()).isEqualTo(3);
            assertThat(body.path("items").size()).isEqualTo(page < 2 ? 10 : 5);
            body.path("items").forEach(item -> seen.add(item.path("id").asString()));
        }

        assertThat(seen).containsExactlyElementsOf(created.reversed());
    }

    @Test
    @Story("Paging Defaults")
    @Description("Without parameters, GET /orders returns the first page with up to 20 orders.")
    void defaultsToFirstPageOfTwenty() throws InterruptedException {
        createOrders(3);

        var body = getJson("/orders");

        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("items").size()).isEqualTo(3);
        assertThat(body.path("items").get(0).path("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    @Story("Paging Beyond The Last Page")
    @Description("A page after the last one is empty but still reports the totals.")
    void pageAfterTheLastIsEmpty() throws InterruptedException {
        createOrders(3);

        var body = getJson("/orders?page=5&size=10");

        assertThat(body.path("items").size()).isZero();
        assertThat(body.path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @Story("Invalid Paging Rejected")
    @Description("A negative page, or a size outside 1-100, is rejected with HTTP 400.")
    void invalidPagingIsRejected() {
        assertThat(status("/orders?page=-1")).isEqualTo(400);
        assertThat(status("/orders?size=0")).isEqualTo(400);
        assertThat(status("/orders?size=101")).isEqualTo(400);
        assertThat(status("/orders?size=100")).isEqualTo(200);
    }

    // --- Allure Step Helpers ---

    @Step("Create {count} draft orders")
    private List<String> createOrders(int count) throws InterruptedException {
        var ids = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            ids.add(service.createDraft());
            // Mongo stores createdAt to the millisecond: keep each order in a millisecond of its own
            Thread.sleep(2);
        }
        return ids;
    }

    @Step("GET {path} and assert HTTP 200")
    private JsonNode getJson(String path) {
        var response = send(path);
        assertThat(response.statusCode()).as("Expected HTTP 200 from %s", path).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    @Step("GET {path} and return the status code")
    private int status(String path) {
        return send(path).statusCode();
    }

    private HttpResponse<String> send(String path) {
        var uri = URI.create("http://localhost:" + port + path);
        try {
            return HTTP.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError("HTTP call to " + uri + " failed: " + e.getMessage(), e);
        }
    }
}
