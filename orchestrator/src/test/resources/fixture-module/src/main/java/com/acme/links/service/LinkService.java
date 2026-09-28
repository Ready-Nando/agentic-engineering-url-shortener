package com.acme.links.service;

import com.acme.links.domain.*;
import com.acme.links.persistence.ClickRepository;
import com.acme.links.persistence.LinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkService {

    private final LinkRepository links;
    private final ClickRepository clicks;
    private final CodeGenerator codes = new CodeGenerator();

    public LinkService(LinkRepository links, ClickRepository clicks) {
        this.links = links;
        this.clicks = clicks;
    }

    @Transactional
    public Link shorten(String targetUrl) {
        return links.insert(codes.next(), targetUrl);
    }

    public Link resolve(String code) {
        Link link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        clicks.record(link.id());
        return link;
    }

    public LinkStats stats(String code) {
        links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        return new LinkStats(code, clicks.countFor(code));
    }

    @Transactional
    public void delete(String code) {
        links.deleteByCode(code);
    }
}
