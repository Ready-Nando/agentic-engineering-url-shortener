package com.example.sdlc.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportsTest {

    private static final String SERVICE_TEST = "com.example.shortener.ShortenerServiceTest";
    private static final String CODEC_TEST = "com.example.shortener.Base62CodecTest";

    @Test
    void summarisesFailuresErrorsAndSkippedTestsAcrossReports() throws Exception {
        SurefireSummary summary = SurefireReports.parse(fixture("mixed"));

        assertThat(summary.testsRun()).isEqualTo(6);
        assertThat(summary.failures()).isEqualTo(1);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.failedTests()).containsExactly(
                new FailedTest(SERVICE_TEST, "rejectsBlankUrl", "expected: <400> but was: <200>"),
                // No message attribute: the exception type is the best available description.
                new FailedTest(SERVICE_TEST, "resolvesExpiredCode", "java.lang.IllegalStateException"));
        assertThat(summary.executedTestClasses()).containsExactly(CODEC_TEST, SERVICE_TEST);
        assertThat(summary.passedTestClasses()).containsExactly(CODEC_TEST);
    }

    @Test
    void missingDirectoryMeansNoTests(@TempDir Path dir) {
        assertThat(SurefireReports.parse(dir.resolve("target/surefire-reports"))).isEqualTo(SurefireSummary.EMPTY);
    }

    @Test
    void skipsTruncatedAndEmptyReports() throws Exception {
        SurefireSummary summary = SurefireReports.parse(fixture("malformed"));

        assertThat(summary.testsRun()).isEqualTo(1);
        assertThat(summary.failures()).isZero();
        assertThat(summary.failedTests()).isEmpty();
        assertThat(summary.executedTestClasses()).containsExactly("com.example.shortener.HealthyTest");
    }

    @Test
    void refusesExternalEntities(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        Files.writeString(dir.resolve("TEST-com.example.EvilTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE testsuite [<!ENTITY secret SYSTEM "%s">]>
                <testsuite name="com.example.EvilTest" tests="1" failures="1" errors="0" skipped="0">
                  <testcase classname="com.example.EvilTest" name="leaks"><failure>&secret;</failure></testcase>
                </testsuite>
                """.formatted(secret.toUri()));
        Files.writeString(dir.resolve("TEST-com.example.PlainTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.PlainTest" tests="1" failures="0" errors="0" skipped="0">
                  <testcase classname="com.example.PlainTest" name="ok"/>
                </testsuite>
                """);

        SurefireSummary summary = SurefireReports.parse(dir);

        assertThat(summary.failedTests()).isEmpty();
        assertThat(summary.toString()).doesNotContain("TOP-SECRET-VALUE");
        assertThat(summary.executedTestClasses()).containsExactly("com.example.PlainTest");
    }

    @Test
    void acceptsTestsuitesWrapperAndCountsFromTestCasesWhenAttributesAreMissing(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("TEST-all.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuites>
                  <testsuite name="com.example.ATest">
                    <testcase classname="com.example.ATest" name="one"/>
                    <testcase classname="com.example.ATest" name="two"><error message="kaput"/></testcase>
                  </testsuite>
                  <testsuite name="com.example.BTest">
                    <testcase classname="com.example.BTest" name="three"><skipped/></testcase>
                    <testcase classname="com.example.BTest" name="four"/>
                  </testsuite>
                </testsuites>
                """);

        SurefireSummary summary = SurefireReports.parse(dir);

        assertThat(summary.testsRun()).isEqualTo(4);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.failedTests()).containsExactly(new FailedTest("com.example.ATest", "two", "kaput"));
        assertThat(summary.passedTestClasses()).containsExactly("com.example.BTest");
    }

    @Test
    void classWhoseTestsWereAllSkippedIsExecutedButNotPassed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("TEST-com.example.DisabledTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.DisabledTest" tests="1" failures="0" errors="0" skipped="1">
                  <testcase classname="com.example.DisabledTest" name="flaky"><skipped message="@Disabled"/></testcase>
                </testsuite>
                """);

        SurefireSummary summary = SurefireReports.parse(dir);

        assertThat(summary.executedTestClasses()).containsExactly("com.example.DisabledTest");
        assertThat(summary.passedTestClasses()).isEmpty();
    }

    @Test
    void countsNeverFallBelowWhatTheTestCasesShow(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("TEST-com.example.LyingTest.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.LyingTest" tests="0" failures="0" errors="-1" skipped="0">
                  <testcase classname="com.example.LyingTest" name="fails"><failure message="boom"/></testcase>
                  <testcase classname="com.example.LyingTest" name="errs"><error message="kaput"/></testcase>
                </testsuite>
                """);

        SurefireSummary summary = SurefireReports.parse(dir);

        assertThat(summary.testsRun()).isEqualTo(2);
        assertThat(summary.failures()).isEqualTo(1);
        assertThat(summary.errors()).isEqualTo(1);
        assertThat(summary.passedTestClasses()).isEmpty();
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(SurefireReportsTest.class.getResource("surefire/" + name).toURI());
    }
}
