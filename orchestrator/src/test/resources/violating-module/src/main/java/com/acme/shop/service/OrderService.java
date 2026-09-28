package com.acme.shop.service;

import com.acme.shop.domain.Order;
import com.acme.shop.persistence.OrderRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public Order place() {
        return repository.save(new Order(0L, "NEW"));
    }
}
