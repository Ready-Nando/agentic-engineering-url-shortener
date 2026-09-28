package com.example.shortener.abuse.api;

import com.example.shortener.abuse.AbuseReportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AbuseReportController {

    private final AbuseReportService abuseReportService;

    public AbuseReportController(AbuseReportService abuseReportService) {
        this.abuseReportService = abuseReportService;
    }

    /**
     * Answers 202 Accepted without a body whether the report is new, repeated or triggered a takedown, so the
     * endpoint reveals neither how many reports a link has nor how close it is to being taken down.
     */
    @PostMapping("/api/v1/links/{code}/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@PathVariable String code, @Valid @RequestBody AbuseReportRequest request,
                       HttpServletRequest servletRequest) {
        // The direct peer address; behind a reverse proxy configure server.forward-headers-strategy.
        abuseReportService.report(code, request.toAbuseReason(), servletRequest.getRemoteAddr());
    }
}
