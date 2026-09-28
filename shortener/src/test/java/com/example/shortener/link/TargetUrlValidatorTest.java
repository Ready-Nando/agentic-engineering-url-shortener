package com.example.shortener.link;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.shortener.config.ShortenerProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetUrlValidatorTest {

    private final TargetUrlValidator validator = new TargetUrlValidator(new ShortenerProperties(
            URI.create("https://sho.rt"), 7, 5, new ShortenerProperties.Stats(7, 90)));

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.org",
            "https://example.org/",
            "HTTPS://Example.org/Path?q=1&r=two#section",
            "https://example.org:8443/a/b",
            "http://192.0.2.10/status",
            "http://[2001:db8::1]/",
            "https://sub.sho.rt/not-the-shortener",
            "https://example.org/caf%C3%A9"
    })
    void acceptsAbsoluteHttpUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    // The documented posture: the service only redirects and never fetches a target, so internal addresses are no
    // server-side request forgery risk here and are not rejected.
    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin",
            "http://localhost:9000/",
            "http://10.0.0.8/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://[fe80::1]/"
    })
    void acceptsPrivateLoopbackAndLinkLocalTargets(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "example.org",
            "/relative/path",
            "//example.org/protocol-relative",
            "ftp://example.org/file.txt",
            "javascript:alert(1)",
            "mailto:someone@example.org",
            "data:text/html,hello",
            "http:opaque",
            "http://",
            "http:///path-without-host",
            "http://exa mple.org",
            "https://user:secret@example.org/",
            "http://google.com@evil.example/login",
            "https://sho.rt/abc1234",
            "http://SHO.RT:8080/abc1234",
            "https://sho.rt./abc1234"
    })
    void rejectsUnsafeOrInvalidUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidTargetUrlException.class);
    }

    @Test
    void enforcesMaximumLength() {
        String prefix = "https://example.org/";
        String atLimit = prefix + "a".repeat(TargetUrlValidator.MAX_LENGTH - prefix.length());

        assertThatCode(() -> validator.validate(atLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(atLimit + "a"))
                .isInstanceOf(InvalidTargetUrlException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void explainsWhyUserInfoIsRejected() {
        assertThatThrownBy(() -> validator.validate("http://google.com@evil.example"))
                .hasMessage("URL must not contain user information");
    }
}
