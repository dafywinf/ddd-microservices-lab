package com.dafywinf.inventory.app;

import com.dafywinf.inventory.domain.StockItem;
import com.dafywinf.inventory.domain.StockItemRepository;
import org.springframework.stereotype.Service;

@Service
public class InventoryApplicationService {

    private final StockItemRepository stock;

    public InventoryApplicationService(StockItemRepository stock) {
        this.stock = stock;
    }

    public void seed(String sku, int available) {
        stock.save(new StockItem(sku, available));
    }

    public void reserve(String sku, int qty) {
        var item = stock.findById(sku).orElseThrow(() -> new IllegalArgumentException("unknown sku=" + sku));
        item.reserve(qty);
        stock.save(item);
    }
}
