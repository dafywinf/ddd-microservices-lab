package com.dafywinf.inventory.domain;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Document("stock_items")
public class StockItem {

    @Id
    private String sku;

    private int available;
    private int reserved;

    protected StockItem() {}

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
        this.reserved = 0;
    }

    public void reserve(int qty) {
        if (qty <= 0) throw new IllegalArgumentException("qty must be > 0");
        if (available < qty) throw new IllegalStateException("insufficient stock for sku=" + sku);
        available -= qty;
        reserved += qty;
    }

}