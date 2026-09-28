package com.acme.links.api;

import com.acme.links.domain.Link;
import com.acme.links.domain.LinkStats;
import com.acme.links.service.LinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * JSON API for short links. Storage goes through the service, never through
 * com.acme.links.persistence.LinkRepository directly.
 */
@RestController
@RequestMapping(path = "/api/links", produces = "application/json")
public class LinkController {

    // Browser redirects live in RedirectController.
    private static final String AUDIT_NOTE = "see RedirectController for redirects";

    private final LinkService links;

    public LinkController(LinkService links) {
        this.links = links;
    }

    @PostMapping
    public ResponseEntity<Link> shorten(@RequestBody ShortenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(links.shorten(request.targetUrl()));
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
    public Link resolve(@PathVariable String code) {
        return links.resolve(code);
    }

    @RequestMapping(value = "/{code}/stats", method = RequestMethod.GET)
    public LinkStats stats(@PathVariable("code") String code) {
        return links.stats(code);
    }

    @DeleteMapping(path = "/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        links.delete(code);
    }
}
