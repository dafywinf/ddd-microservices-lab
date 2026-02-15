package com.dafywinf.order.domain;

import com.dafywinf.order.domain.OrderLine;
import com.dafywinf.order.domain.OrderStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Document("orders")
public class Order {

    @Id
    private String id;

    private OrderStatus status;

    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {}

    public static Order draft() {
        Order o = new Order();
        o.id = UUID.randomUUID().toString();
        o.status = OrderStatus.DRAFT;
        return o;
    }

    public void addLine(String sku, int quantity) {
        if (status != OrderStatus.DRAFT) throw new IllegalStateException("can only add lines to DRAFT orders");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        lines.add(new OrderLine(sku, quantity));
    }

    public void place() {
        if (status != OrderStatus.DRAFT) throw new IllegalStateException("only DRAFT orders can be placed");
        if (lines.isEmpty()) throw new IllegalStateException("cannot place an order with no lines");
        status = OrderStatus.PLACED;
    }

    public void confirm() {
        if (status != OrderStatus.PLACED) throw new IllegalStateException("only PLACED orders can be confirmed");
        status = OrderStatus.CONFIRMED;
    }

    public void reject(String reason) {
        if (status != OrderStatus.PLACED) throw new IllegalStateException("only PLACED orders can be rejected");
        status = OrderStatus.REJECTED;
    }

    public String getId() { return id; }
    public OrderStatus getStatus() { return status; }
    public List<OrderLine> getLines() { return List.copyOf(lines); }
}
