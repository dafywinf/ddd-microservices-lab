package com.dafywinf.inventory.api;

import com.dafywinf.inventory.app.InventoryApplicationService;
import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Tag(name = "Inventory")
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    static final int MAX_PAGE_SIZE = 100;

    private final InventoryApplicationService service;
    private final StockItemRepository stock;

    public InventoryController(InventoryApplicationService service, StockItemRepository stock) {
        this.service = service;
        this.stock = stock;
    }

    @Operation(summary = "Set the available stock for a SKU")
    @PostMapping("/seed")
    public ResponseEntity<Void> seed(@RequestBody SeedRequest req) {
        service.seed(req.sku(), req.available());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List stock by SKU, one page at a time")
    @GetMapping
    public InventoryPage list(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and " + MAX_PAGE_SIZE);
        }
        // The SKU is the id, so it is unique: the order is stable and no item appears on two pages
        var result = stock.findAll(PageRequest.of(page, size, Sort.by("sku")));
        return new InventoryPage(result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    public record SeedRequest(String sku, int available) {}
    public record InventoryPage(List<StockItem> items, int page, int size, long totalElements, int totalPages) {}
}
