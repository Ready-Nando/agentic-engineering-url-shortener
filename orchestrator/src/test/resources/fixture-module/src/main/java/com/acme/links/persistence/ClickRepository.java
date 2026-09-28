package com.acme.links.persistence;

public interface ClickRepository {

    void record(long linkId);

    long countFor(String code);
}
