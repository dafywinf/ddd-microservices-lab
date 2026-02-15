package com.dafywinf.order.api;

import com.dafywinf.order.app.OrderApplicationService;
import com.dafywinf.order.domain.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderApplicationService service;
    private final OrderRepository repo;

    public OrderController(OrderApplicationService service, OrderRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<CreateOrderResponse> create() {
        return ResponseEntity.ok(new CreateOrderResponse(service.createDraft()));
    }

    @PostMapping("/{id}/lines")
    public ResponseEntity<Void> addLine(@PathVariable String id, @RequestBody AddLineRequest req) {
        service.addLine(id, req.sku(), req.quantity());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/place")
    public ResponseEntity<Void> place(@PathVariable String id) {
        service.placeOrder(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        return repo.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateOrderResponse(String id) {}
    public record AddLineRequest(String sku, int quantity) {}
}
