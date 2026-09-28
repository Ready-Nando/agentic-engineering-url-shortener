package com.example.shortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClickRecorderTest {

    @ParameterizedTest
    @CsvSource({
            "https://news.example.org/story?id=1, news.example.org",
            "https://News.Example.ORG/Search?q=my+private+query, news.example.org",
            "http://blog.example.com:8080/a/b#frag, blog.example.com",
            "'  https://padded.example/  ', padded.example",
            "android-app://com.example.mail/, com.example.mail"
    })
    void keepsOnlyTheLowerCasedHost(String referer, String expectedHost) {
        assertThat(ClickRecorder.referrerHost(referer)).isEqualTo(expectedHost);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "/relative/path", "about:blank", "https://"})
    void dropsMissingOrUnparseableReferrers(String referer) {
        assertThat(ClickRecorder.referrerHost(referer)).isNull();
    }
}
