package com.dafywinf.inventory.api;

import com.dafywinf.inventory.app.InventoryApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryApplicationService service;

    public InventoryController(InventoryApplicationService service) {
        this.service = service;
    }

    @PostMapping("/seed")
    public ResponseEntity<Void> seed(@RequestBody SeedRequest req) {
        service.seed(req.sku(), req.available());
        return ResponseEntity.noContent().build();
    }

    public record SeedRequest(String sku, int available) {}
}
