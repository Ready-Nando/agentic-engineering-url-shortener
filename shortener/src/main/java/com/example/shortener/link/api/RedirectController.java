package com.example.shortener.link.api;

import com.example.shortener.analytics.ClickRecorder;
import com.example.shortener.link.LinkService;
import com.example.shortener.link.ShortLink;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final LinkService linkService;
    private final ClickRecorder clickRecorder;

    public RedirectController(LinkService linkService, ClickRecorder clickRecorder) {
        this.linkService = linkService;
        this.clickRecorder = clickRecorder;
    }

    /**
     * Answers with 302 Found and {@code Cache-Control: no-store} rather than 301 Moved Permanently:
     * browsers and proxies cache 301s indefinitely, so later clicks would never reach the service.
     * That would silently break click analytics and keep disabled links working for anyone who had
     * followed them before.
     */
    @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
    public ResponseEntity<Void> redirect(
            @PathVariable String code,
            @RequestHeader(name = HttpHeaders.REFERER, required = false) @Nullable String referer,
            HttpMethod method) {
        ShortLink link = linkService.resolve(code);
        // Spring also routes HEAD here; link-preview bots and uptime checkers use it, and those are not clicks.
        if (method == HttpMethod.GET) {
            clickRecorder.record(link, referer);
        }
        // Going through URI percent-encodes any non-ASCII characters, which are not allowed in a header.
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.targetUrl()))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
