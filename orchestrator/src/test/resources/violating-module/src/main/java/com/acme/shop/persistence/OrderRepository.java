package com.acme.shop.persistence;

import com.acme.shop.domain.Order;
import com.acme.shop.service.OrderService;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcClient jdbc;
    private final OrderService audit; // persistence reaching back up into the service layer

    public OrderRepository(JdbcClient jdbc, OrderService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Order save(Order order) {
        jdbc.sql("INSERT INTO orders (status) VALUES (?)").param(order.status()).update();
        return order;
    }

    public Optional<Order> findById(long id) {
        return jdbc.sql("SELECT id, status FROM orders WHERE id = ?").param(id).query(Order.class).optional();
    }
}
