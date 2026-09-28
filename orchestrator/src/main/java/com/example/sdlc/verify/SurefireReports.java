package com.example.sdlc.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Reads {@code TEST-*.xml} files written by Surefire. Reports come from the build under verification, so
 * they are untrusted input: DTDs are refused outright, and files that cannot be parsed are skipped.
 */
public final class SurefireReports {

    private SurefireReports() {
    }

    /** Never throws for missing directories or unreadable reports; those simply contribute nothing. */
    public static SurefireSummary parse(Path reportsDirectory) {
        List<Path> reportFiles;
        try (Stream<Path> files = Files.list(reportsDirectory)) {
            // Sorted so that class lists come out in a stable order.
            reportFiles = files.filter(SurefireReports::isReportFile).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return SurefireSummary.EMPTY;
        }

        DocumentBuilder builder = newSecureBuilder();
        Totals totals = new Totals();
        for (Path file : reportFiles) {
            Element root;
            try {
                root = builder.parse(file.toFile()).getDocumentElement();
            } catch (SAXException | IOException e) {
                continue;
            }
            switch (root.getTagName()) {
                case "testsuite" -> totals.add(root);
                case "testsuites" -> childElements(root, "testsuite").forEach(totals::add);
                default -> {
                    // Not a test report.
                }
            }
        }
        return totals.toSummary();
    }

    private static boolean isReportFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("TEST-") && name.endsWith(".xml") && Files.isRegularFile(path);
    }

    private static DocumentBuilder newSecureBuilder() {
        try {
            // The JDK's own parser, not whatever implementation happens to be on the classpath: the features
            // below are only known to be honoured (and not rejected with an exception) by the built-in one.
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // The default handler prints "[Fatal Error]" to stderr before throwing.
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
    }

    private static List<Element> childElements(Element parent, String tagName) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(tagName)) {
                children.add(element);
            }
        }
        return children;
    }

    private static Element firstChild(Element parent, String tagName) {
        List<Element> children = childElements(parent, tagName);
        return children.isEmpty() ? null : children.getFirst();
    }

    private static int count(List<Element> testcases, String childTag) {
        return (int) testcases.stream().filter(testcase -> firstChild(testcase, childTag) != null).count();
    }

    /**
     * The suite's declared count, but never less than what its test cases show: a missing, garbled or
     * understated attribute must not hide a failure that is plainly in the report.
     */
    private static int suiteCount(Element suite, String attribute, int counted) {
        try {
            return Math.max(counted, Integer.parseInt(suite.getAttribute(attribute).trim()));
        } catch (NumberFormatException e) {
            return counted;
        }
    }

    private static String problemMessage(Element problem) {
        String message = problem.getAttribute("message");
        if (!message.isBlank()) {
            return message;
        }
        String type = problem.getAttribute("type");
        if (!type.isBlank()) {
            return type;
        }
        return problem.getTextContent().strip().lines().findFirst().orElse("");
    }

    private static final class Totals {

        private int testsRun;
        private int failures;
        private int errors;
        private int skipped;
        private final List<FailedTest> failedTests = new ArrayList<>();
        private final Set<String> executedClasses = new LinkedHashSet<>();
        private final Set<String> classesWithProblems = new HashSet<>();
        private final Set<String> classesThatRanTests = new HashSet<>();

        void add(Element suite) {
            String suiteName = suite.getAttribute("name").strip();
            List<Element> testcases = childElements(suite, "testcase");
            int suiteTests = suiteCount(suite, "tests", testcases.size());
            int suiteFailures = suiteCount(suite, "failures", count(testcases, "failure"));
            int suiteErrors = suiteCount(suite, "errors", count(testcases, "error"));
            int suiteSkipped = suiteCount(suite, "skipped", count(testcases, "skipped"));

            testsRun += suiteTests;
            failures += suiteFailures;
            errors += suiteErrors;
            skipped += suiteSkipped;

            for (Element testcase : testcases) {
                Element problem = firstChild(testcase, "failure");
                if (problem == null) {
                    problem = firstChild(testcase, "error");
                }
                if (problem != null) {
                    String className = testcase.getAttribute("classname");
                    failedTests.add(new FailedTest(
                            className.isBlank() ? suiteName : className,
                            testcase.getAttribute("name"),
                            problemMessage(problem)));
                }
            }

            if (!suiteName.isEmpty()) {
                executedClasses.add(suiteName);
                if (suiteFailures + suiteErrors > 0) {
                    classesWithProblems.add(suiteName);
                }
                if (suiteTests > suiteSkipped) {
                    classesThatRanTests.add(suiteName);
                }
            }
        }

        SurefireSummary toSummary() {
            // A class whose every test was skipped (e.g. @Disabled) proved nothing, so it does not count as passed.
            List<String> passed = executedClasses.stream()
                    .filter(name -> classesThatRanTests.contains(name) && !classesWithProblems.contains(name))
                    .toList();
            return new SurefireSummary(
                    testsRun, failures, errors, skipped, failedTests, passed, List.copyOf(executedClasses));
        }
    }
}
