package com.example.shortener.link.api;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.link.CreateLinkCommand;
import com.example.shortener.link.CreateLinkResult;
import com.example.shortener.link.LinkService;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED_HEADER = "Idempotent-Replayed";

    private final LinkService linkService;
    private final ShortenerProperties properties;

    public LinkController(LinkService linkService, ShortenerProperties properties) {
        this.linkService = linkService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) @Nullable String idempotencyKey) {
        CreateLinkResult result = linkService.create(
                new CreateLinkCommand(request.url(), request.alias(), idempotencyKey));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/links/{code}")
                .buildAndExpand(result.link().code())
                .toUri();
        LinkResponse body = LinkResponse.from(result.link(), properties);
        if (result.replayed()) {
            return ResponseEntity.ok()
                    .location(location)
                    .header(IDEMPOTENT_REPLAYED_HEADER, "true")
                    .body(body);
        }
        return ResponseEntity.created(location).body(body);
    }

    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        return LinkResponse.from(linkService.get(code), properties);
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@PathVariable String code) {
        linkService.disable(code);
    }
}
