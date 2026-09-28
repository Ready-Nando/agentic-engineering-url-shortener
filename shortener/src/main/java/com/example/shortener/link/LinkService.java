package com.example.shortener.link;

import com.example.shortener.config.ShortenerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Link lifecycle. Deliberately not {@code @Transactional}: every write is a single statement, and
 * collision handling relies on catching a failed INSERT and trying again. Inside a transaction that
 * would not work on PostgreSQL, which aborts the whole transaction after the first failed statement.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final LinkRepository linkRepository;
    private final ShortCodeGenerator codeGenerator;
    private final TargetUrlValidator targetUrlValidator;
    private final AliasPolicy aliasPolicy;
    private final ShortenerProperties properties;
    private final Clock clock;
    private final Counter linksCreated;
    private final Counter redirectsFound;
    private final Counter redirectsNotFound;
    private final Counter redirectsGone;

    public LinkService(LinkRepository linkRepository, ShortCodeGenerator codeGenerator,
                       TargetUrlValidator targetUrlValidator, AliasPolicy aliasPolicy,
                       ShortenerProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.linkRepository = linkRepository;
        this.codeGenerator = codeGenerator;
        this.targetUrlValidator = targetUrlValidator;
        this.aliasPolicy = aliasPolicy;
        this.properties = properties;
        this.clock = clock;
        this.linksCreated = meterRegistry.counter("shortener.links.created");
        this.redirectsFound = meterRegistry.counter("shortener.redirects", "outcome", "found");
        this.redirectsNotFound = meterRegistry.counter("shortener.redirects", "outcome", "not_found");
        this.redirectsGone = meterRegistry.counter("shortener.redirects", "outcome", "gone");
    }

    public CreateLinkResult create(CreateLinkCommand command) {
        targetUrlValidator.validate(command.targetUrl());
        if (command.alias() != null) {
            aliasPolicy.validate(command.alias());
        }
        String requestHash = null;
        if (command.idempotencyKey() != null) {
            if (!IDEMPOTENCY_KEY_PATTERN.matcher(command.idempotencyKey()).matches()) {
                throw new InvalidIdempotencyKeyException();
            }
            requestHash = requestHash(command);
            Optional<CreateLinkResult> replay = replay(command.idempotencyKey(), requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        CreateLinkResult result = command.alias() != null
                ? insertWithAlias(command, requestHash)
                : insertWithGeneratedCode(command, requestHash);
        if (!result.replayed()) {
            linksCreated.increment();
        }
        return result;
    }

    public ShortLink get(String code) {
        return linkRepository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    /**
     * Looks up the link a redirect should go to.
     *
     * @throws LinkNotFoundException if no link has this code
     * @throws LinkGoneException if the link exists but was disabled
     */
    public ShortLink resolve(String code) {
        Optional<ShortLink> link = linkRepository.findByCode(code);
        if (link.isEmpty()) {
            redirectsNotFound.increment();
            throw new LinkNotFoundException(code);
        }
        if (!link.get().isActive()) {
            redirectsGone.increment();
            throw new LinkGoneException(code);
        }
        redirectsFound.increment();
        return link.get();
    }

    /**
     * Soft-disables a link. The row is kept so its analytics survive and its code is never handed out again.
     * Disabling an already disabled link is a no-op.
     */
    public void disable(String code) {
        ShortLink link = get(code);
        if (link.isActive()) {
            linkRepository.disable(code, clock.instant());
        }
    }

    private CreateLinkResult insertWithAlias(CreateLinkCommand command, @Nullable String requestHash) {
        try {
            return new CreateLinkResult(insert(command.alias(), command, requestHash), false);
        } catch (DuplicateKeyException e) {
            return replayAfterConflict(command, requestHash)
                    .orElseThrow(() -> new AliasUnavailableException(command.alias()));
        }
    }

    private CreateLinkResult insertWithGeneratedCode(CreateLinkCommand command, @Nullable String requestHash) {
        int maxAttempts = properties.maxCodeGenerationAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.generate();
            // Reserved words stay free for top-level routes; a generated "error" would even be unreachable.
            if (aliasPolicy.isReserved(code)) {
                continue;
            }
            try {
                return new CreateLinkResult(insert(code, command, requestHash), false);
            } catch (DuplicateKeyException e) {
                Optional<CreateLinkResult> replay = replayAfterConflict(command, requestHash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                log.info("Generated code collided with an existing link (attempt {}/{})", attempt, maxAttempts);
            }
        }
        throw new CodeGenerationException(maxAttempts);
    }

    private ShortLink insert(String code, CreateLinkCommand command, @Nullable String requestHash) {
        return linkRepository.insert(code, command.targetUrl(), clock.instant(), command.idempotencyKey(), requestHash);
    }

    // A unique violation can also come from the idempotency key when a concurrent request with the
    // same key committed between our lookup and our insert. That request's link is then the answer.
    private Optional<CreateLinkResult> replayAfterConflict(CreateLinkCommand command, @Nullable String requestHash) {
        if (command.idempotencyKey() == null) {
            return Optional.empty();
        }
        return replay(command.idempotencyKey(), requestHash);
    }

    private Optional<CreateLinkResult> replay(String idempotencyKey, String requestHash) {
        return linkRepository.findByIdempotencyKey(idempotencyKey).map(existing -> {
            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReuseException(idempotencyKey);
            }
            return new CreateLinkResult(existing.link(), true);
        });
    }

    static String requestHash(CreateLinkCommand command) {
        // Neither a parsed URL nor an alias can contain a line feed, so the encoding is unambiguous.
        String canonical = command.targetUrl() + "\n" + (command.alias() == null ? "" : command.alias());
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
