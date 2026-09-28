package com.acme.shop.domain;

import com.acme.shop.service.OrderService;

/** Exposed over HTTP by {@link com.acme.shop.web.OrderController}. */
public record Order(long id, String status) {

    public Order reorder(OrderService service) {
        return service.place();
    }
}
