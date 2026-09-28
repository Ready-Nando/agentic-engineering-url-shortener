package com.example.shortener.abuse;

import com.example.shortener.link.LinkGoneException;
import com.example.shortener.link.LinkNotFoundException;
import com.example.shortener.link.LinkService;
import com.example.shortener.link.ShortLink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records abuse reports and takes a link down once the {@link TakedownPolicy} says so, disabling it exactly as
 * {@code DELETE /api/v1/links/{code}} does.
 * <p>
 * Deliberately not {@code @Transactional}, like {@link LinkService}: a repeated report is detected by a failed
 * INSERT, which would abort a surrounding transaction on PostgreSQL. Every step is a single statement and
 * disabling is idempotent, so concurrent reports that both reach the threshold are harmless.
 */
@Service
public class AbuseReportService {

    private static final Logger log = LoggerFactory.getLogger(AbuseReportService.class);

    private final LinkService linkService;
    private final AbuseReportRepository reports;
    private final ReporterFingerprint reporterFingerprint;
    private final TakedownPolicy takedownPolicy;
    private final Clock clock;
    private final Map<AbuseReason, Counter> reportsByReason = new EnumMap<>(AbuseReason.class);
    private final Counter takedowns;

    public AbuseReportService(LinkService linkService, AbuseReportRepository reports, ReporterFingerprint reporterFingerprint,
                              TakedownPolicy takedownPolicy, Clock clock, MeterRegistry meterRegistry) {
        this.linkService = linkService;
        this.reports = reports;
        this.reporterFingerprint = reporterFingerprint;
        this.takedownPolicy = takedownPolicy;
        this.clock = clock;
        for (AbuseReason reason : AbuseReason.values()) {
            reportsByReason.put(reason, meterRegistry.counter("shortener.abuse.reports", "reason", reason.name()));
        }
        this.takedowns = meterRegistry.counter("shortener.abuse.takedowns");
    }

    /**
     * @param clientAddress only used to derive the reporter's per-link pseudonym; never stored
     * @throws LinkNotFoundException if no link has this code
     * @throws LinkGoneException if the link is already disabled
     */
    public void report(String code, AbuseReason reason, String clientAddress) {
        // get() rather than resolve(): resolve() counts redirect outcomes, and a report is not a redirect.
        ShortLink link = linkService.get(code);
        if (!link.isActive()) {
            throw new LinkGoneException(code);
        }
        Instant now = clock.instant();
        reports.save(new AbuseReport(link.id(), reporterFingerprint.of(link.id(), clientAddress), reason, now));
        reportsByReason.get(reason).increment();

        long reporters = reports.countReportersSince(link.id(), takedownPolicy.windowStart(now));
        if (takedownPolicy.requiresTakedown(reporters)) {
            linkService.disable(code);
            takedowns.increment();
            log.warn("Link '{}' disabled automatically after abuse reports from {} distinct reporters", code, reporters);
        }
    }
}
