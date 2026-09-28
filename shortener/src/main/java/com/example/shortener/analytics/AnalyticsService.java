package com.example.shortener.analytics;

import com.example.shortener.analytics.LinkStats.DailyClicks;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.link.LinkService;
import com.example.shortener.link.ShortLink;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    private static final int TOP_REFERRER_LIMIT = 5;

    private final LinkService linkService;
    private final ClickEventRepository clickEventRepository;
    private final ShortenerProperties properties;
    private final Clock clock;

    public AnalyticsService(LinkService linkService, ClickEventRepository clickEventRepository,
                            ShortenerProperties properties, Clock clock) {
        this.linkService = linkService;
        this.clickEventRepository = clickEventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Statistics over the last {@code days} UTC calendar days, today included. Disabled links still
     * report their history.
     * <p>
     * The queries share one REPEATABLE READ snapshot so that totals, window count and daily series agree
     * while clicks keep arriving; under READ COMMITTED every statement would see a different state.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LinkStats stats(String code, int days) {
        int maxDays = properties.stats().maxDays();
        if (days < 1 || days > maxDays) {
            throw new InvalidStatsWindowException(days, maxDays);
        }
        ShortLink link = linkService.get(code);

        LocalDate to = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = to.minusDays(days - 1L);
        Instant windowStart = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> clicksByDate = clickEventRepository.dailyCounts(link.id(), windowStart, windowEnd).stream()
                .collect(Collectors.toMap(DailyClicks::date, DailyClicks::clicks));
        List<DailyClicks> daily = from.datesUntil(to.plusDays(1))
                .map(date -> new DailyClicks(date, clicksByDate.getOrDefault(date, 0L)))
                .toList();

        return new LinkStats(
                link.code(),
                clickEventRepository.countByLink(link.id()),
                clickEventRepository.lastClickAt(link.id()).orElse(null),
                from,
                to,
                days,
                clickEventRepository.countByLinkBetween(link.id(), windowStart, windowEnd),
                daily,
                clickEventRepository.topReferrers(link.id(), windowStart, windowEnd, TOP_REFERRER_LIMIT));
    }
}
