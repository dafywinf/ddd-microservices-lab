package com.dafywinf.inventory;

import com.dafywinf.inventory.app.InventoryApplicationService;
import com.dafywinf.inventory.app.OutboxPublisher;
import com.dafywinf.inventory.domain.StockItemRepository;
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
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Inventory Management")
@Feature("Stock Queries")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"orders.v1", "inventory.v1"})
class ListInventoryTest {

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
                        + "/inventory_db?directConnection=true&replicaSet=rs0");
    }

    @MockitoBean
    OutboxPublisher outboxPublisher;

    @LocalServerPort
    int port;

    @Autowired InventoryApplicationService service;
    @Autowired StockItemRepository          stockRepo;

    @BeforeEach
    void resetDatabase() {
        stockRepo.deleteAll();
    }

    @Test
    @Story("Paging Through Stock")
    @Description("Seeds 25 SKUs in random order and reads them 10 at a time: 3 pages of 10, 10 and 5, sorted by SKU, each SKU exactly once.")
    void pagesThroughAllStockBySku() {
        var skus = seedSkus(25);

        var seen = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var body = getJson("/inventory?page=" + page + "&size=10");
            assertThat(body.path("page").asInt()).isEqualTo(page);
            assertThat(body.path("size").asInt()).isEqualTo(10);
            assertThat(body.path("totalElements").asLong()).isEqualTo(25);
            assertThat(body.path("totalPages").asInt()).isEqualTo(3);
            assertThat(body.path("items").size()).isEqualTo(page < 2 ? 10 : 5);
            body.path("items").forEach(item -> seen.add(item.path("sku").asString()));
        }

        assertThat(seen).containsExactlyElementsOf(skus.stream().sorted().toList());
    }

    @Test
    @Story("Stock Levels Listed")
    @Description("Each item shows its SKU with the available and reserved quantities.")
    void itemsShowAvailableAndReservedStock() {
        service.seed("SKU-LIST", 7);

        var item = getJson("/inventory").path("items").get(0);

        assertThat(item.path("sku").asString()).isEqualTo("SKU-LIST");
        assertThat(item.path("available").asInt()).isEqualTo(7);
        assertThat(item.path("reserved").asInt()).isZero();
    }

    @Test
    @Story("Paging Defaults")
    @Description("Without parameters, GET /inventory returns the first page with up to 20 items.")
    void defaultsToFirstPageOfTwenty() {
        seedSkus(3);

        var body = getJson("/inventory");

        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("items").size()).isEqualTo(3);
    }

    @Test
    @Story("Paging Beyond The Last Page")
    @Description("A page after the last one is empty but still reports the totals.")
    void pageAfterTheLastIsEmpty() {
        seedSkus(3);

        var body = getJson("/inventory?page=5&size=10");

        assertThat(body.path("items").size()).isZero();
        assertThat(body.path("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @Story("Invalid Paging Rejected")
    @Description("A negative page, or a size outside 1-100, is rejected with HTTP 400.")
    void invalidPagingIsRejected() {
        assertThat(status("/inventory?page=-1")).isEqualTo(400);
        assertThat(status("/inventory?size=0")).isEqualTo(400);
        assertThat(status("/inventory?size=101")).isEqualTo(400);
        assertThat(status("/inventory?size=100")).isEqualTo(200);
    }

    // --- Allure Step Helpers ---

    @Step("Seed {count} SKUs in shuffled order")
    private List<String> seedSkus(int count) {
        var skus = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            skus.add("SKU-%03d".formatted(i));
        }
        var shuffled = new ArrayList<>(skus);
        Collections.shuffle(shuffled);
        shuffled.forEach(sku -> service.seed(sku, 10));
        return skus;
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
