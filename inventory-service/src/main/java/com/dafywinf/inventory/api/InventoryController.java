package com.dafywinf.inventory.api;

import com.dafywinf.inventory.app.InventoryApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Inventory")
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private final InventoryApplicationService service;

    public InventoryController(InventoryApplicationService service) {
        this.service = service;
    }

    @Operation(summary = "Set the available stock for a SKU")
    @PostMapping("/seed")
    public ResponseEntity<Void> seed(@RequestBody SeedRequest req) {
        service.seed(req.sku(), req.available());
        return ResponseEntity.noContent().build();
    }

    public record SeedRequest(String sku, int available) {}
}
