package com.acme.shop.web;

import com.acme.shop.domain.Order;
import com.acme.shop.persistence.OrderRepository;
import com.acme.shop.service.OrderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orders;
    private final OrderRepository repository; // a shortcut past the service layer

    OrderController(OrderService orders, OrderRepository repository) {
        this.orders = orders;
        this.repository = repository;
    }

    @GetMapping("/{id}")
    public Order find(@PathVariable long id) {
        return repository.findById(id).orElseThrow();
    }

    @PostMapping
    public Order place() {
        return orders.place();
    }
}
