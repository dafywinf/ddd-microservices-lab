package com.dafywinf.order.api;

import com.dafywinf.order.app.OrderApplicationService;
import com.dafywinf.order.domain.Order;
import com.dafywinf.order.domain.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Orders")
@RestController
@RequestMapping("/orders")
public class OrderController {

    static final int MAX_PAGE_SIZE = 100;

    private final OrderApplicationService service;
    private final OrderRepository repo;

    public OrderController(OrderApplicationService service, OrderRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @Operation(summary = "Create a draft order")
    @PostMapping
    public ResponseEntity<CreateOrderResponse> create() {
        return ResponseEntity.ok(new CreateOrderResponse(service.createDraft()));
    }

    @Operation(summary = "Add a line to a draft order")
    @PostMapping("/{id}/lines")
    public ResponseEntity<Void> addLine(@PathVariable String id, @RequestBody AddLineRequest req) {
        service.addLine(id, req.sku(), req.quantity());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Place an order (publishes OrderPlaced)")
    @PostMapping("/{id}/place")
    public ResponseEntity<Void> place(@PathVariable String id) {
        service.placeOrder(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List orders, newest first, one page at a time")
    @GetMapping
    public OrderPage list(@RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // id breaks ties between orders created in the same millisecond (Mongo stores dates to the millisecond),
        // so the order is stable and no order appears on two pages
        var sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        var result = repo.findAll(PageRequest.of(page, size, sort));
        return new OrderPage(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Operation(summary = "Get an order and its status")
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return repo.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateOrderResponse(String id) {}
    public record AddLineRequest(String sku, int quantity) {}
    public record OrderPage(List<Order> items, int page, int size, long totalElements, int totalPages) {}
}
