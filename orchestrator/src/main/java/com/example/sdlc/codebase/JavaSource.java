package com.example.sdlc.codebase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Java file reduced to what token-level analysis needs. This is deliberately not a parser.
 *
 * <p>A single lexing pass blanks comments and the contents of string, char and text-block literals
 * (literals are kept separately), so {@link #code} has the same offsets as the original text and every
 * brace, parenthesis and identifier left in it is real code. Structure is then recovered with regexes
 * plus brace/paren counting: package, imports, top-level types (nested types are part of their
 * enclosing type), annotations and the name of the method an annotation sits on. This holds for
 * conventionally written code; unicode escapes, and generics or annotations in unusual positions, are
 * not understood.
 */
final class JavaSource {

    record Span(int start, int end) {
    }

    /** {@code start}/{@code end} include the quotes; {@code value} is the unescaped content. */
    record Literal(int start, int end, String value) {
    }

    record Import(String name, boolean isStatic, boolean wildcard) {
    }

    /** {@code args} is the text between the parentheses, or null when the annotation has none. */
    record Annotation(String name, int start, int end, Span args) {
    }

    /**
     * A top-level type. Its region runs from the end of the previous top-level type (or of the imports)
     * to its closing brace, so it includes the type's own annotations and Javadoc.
     */
    record TypeDecl(String name, String kind, int regionStart, int bodyStart, int end, List<Annotation> annotations) {
    }

    record Attribute(String name, Span value) {
    }

    private static final String IDENT = "[A-Za-z_$][\\w$]*";
    // Repeated groups are possessive: java.util.regex recurses once per iteration of a backtracking group,
    // so a dotted chain of a few thousand segments would otherwise overflow the stack.
    private static final String QUALIFIED = IDENT + "(?:\\s*\\.\\s*" + IDENT + ")*+";
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+(" + QUALIFIED + ")\\s*;");
    private static final Pattern IMPORT =
            Pattern.compile("\\bimport\\s+(static\\s+)?(" + QUALIFIED + ")(\\s*\\.\\s*\\*)?\\s*;");
    private static final Pattern TYPE_DECL =
            Pattern.compile("(?<![\\w$.@])(@\\s*interface|class|interface|enum|record)\\s+(" + IDENT + ")");
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*(" + QUALIFIED + ")");
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("\\s*(" + IDENT + ")\\s*=(?!=)");
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w$])" + IDENT);
    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("(?<![\\w$.])" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")++");
    // A binary plus, not ++ or +=.
    private static final Pattern TRAILING_CONCATENATION = Pattern.compile("\\s*\\+(?![+=])");

    private final String code;
    private final int[] depth;
    private final List<Literal> literals;
    private final String packageName;
    private final List<Import> imports = new ArrayList<>();
    private final List<TypeDecl> types = new ArrayList<>();

    private JavaSource(String code, List<Literal> literals) {
        this.code = code;
        this.literals = literals;
        this.depth = braceDepths(code);

        int headerEnd = 0;
        Matcher pkg = PACKAGE.matcher(code);
        if (pkg.find()) {
            packageName = withoutWhitespace(pkg.group(1));
            headerEnd = pkg.end();
        } else {
            packageName = "";
        }
        Matcher imp = IMPORT.matcher(code);
        while (imp.find()) {
            if (depth[imp.start()] == 0) {
                imports.add(new Import(withoutWhitespace(imp.group(2)), imp.group(1) != null, imp.group(3) != null));
                headerEnd = Math.max(headerEnd, imp.end());
            }
        }
        findTypes(headerEnd);
    }

    static JavaSource parse(String text) {
        char[] code = text.toCharArray();
        List<Literal> literals = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int newline = text.indexOf('\n', i);
                int end = newline < 0 ? n : newline;
                blank(code, i, end);
                i = end;
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                int end = close < 0 ? n : close + 2;
                blank(code, i, end);
                i = end;
            } else if (text.startsWith("\"\"\"", i)) {
                int close = closingDelimiter(text, i + 3, "\"\"\"", true);
                int end = Math.min(n, close + 3);
                literals.add(new Literal(i, end, unescape(text.substring(i + 3, close))));
                blank(code, i + 3, close);
                i = end;
            } else if (c == '"' || c == '\'') {
                int close = closingDelimiter(text, i + 1, String.valueOf(c), false);
                int end = Math.min(n, close + 1);
                if (c == '"') {
                    literals.add(new Literal(i, end, unescape(text.substring(i + 1, close))));
                }
                blank(code, i + 1, close);
                i = end;
            } else {
                i++;
            }
        }
        return new JavaSource(new String(code), List.copyOf(literals));
    }

    String packageName() {
        return packageName;
    }

    List<Import> imports() {
        return List.copyOf(imports);
    }

    List<TypeDecl> types() {
        return List.copyOf(types);
    }

    String text(Span span) {
        return code.substring(span.start(), span.end());
    }

    /** Identifier tokens in code (never in comments or literals) within the range. */
    List<String> identifiers(int start, int end) {
        return IDENTIFIER.matcher(code).region(start, end).results().map(r -> r.group()).toList();
    }

    /** Dotted names such as {@code com.acme.Foo} or {@code repository.save}, whitespace removed. */
    List<String> qualifiedNames(int start, int end) {
        return QUALIFIED_NAME.matcher(code).region(start, end).results().map(r -> withoutWhitespace(r.group())).toList();
    }

    /**
     * String literals starting within the range, with literals joined by {@code +} concatenated, so that
     * {@code "SELECT ... FROM a " + "JOIN b"} is seen as one statement. Concatenation with non-literal
     * operands (constants, variables) ends the chain; those parts are unknown.
     */
    List<String> literalChains(int start, int end) {
        return literalChains(start, end, null);
    }

    /**
     * Like {@link #literalChains(int, int)}, except that a non-literal operand is replaced by {@code operand}
     * (when not null) instead of ending the chain: {@code "/links/" + code + "/stats"} and
     * {@code "/links/" + link.code()} give {@code /links/{}/stats} and {@code /links/{}} for {@code "{}"}. An
     * operand between two literals must stay within one argument: balanced parentheses, no comma outside
     * them and no {@code ;} or braces.
     */
    List<String> literalChains(int start, int end, String operand) {
        List<String> chains = new ArrayList<>();
        StringBuilder chain = null;
        int chainEnd = -1;
        for (Literal literal : literals) {
            if (literal.start() < start || literal.start() >= end) {
                continue;
            }
            String between = chain == null ? "" : code.substring(chainEnd, literal.start()).strip();
            if (chain != null && between.equals("+")) {
                chain.append(literal.value());
            } else if (chain != null && operand != null && isConcatenatedOperand(between)) {
                chain.append(operand).append(literal.value());
            } else {
                if (chain != null) {
                    chains.add(endChain(chain, chainEnd, operand));
                }
                chain = new StringBuilder(literal.value());
            }
            chainEnd = literal.end();
        }
        if (chain != null) {
            chains.add(endChain(chain, chainEnd, operand));
        }
        return chains;
    }

    /** The chain, followed by the operand when the code after its last literal concatenates something else. */
    private String endChain(StringBuilder chain, int chainEnd, String operand) {
        if (operand != null && TRAILING_CONCATENATION.matcher(code).region(chainEnd, code.length()).lookingAt()) {
            chain.append(operand);
        }
        return chain.toString();
    }

    /** {@code + expression +} where the expression stays within one argument of a call. */
    private static boolean isConcatenatedOperand(String between) {
        if (between.length() < 3 || between.charAt(0) != '+' || between.charAt(between.length() - 1) != '+'
                || between.substring(1, between.length() - 1).isBlank()) {
            return false;
        }
        int parens = 0;
        for (int i = 1; i < between.length() - 1; i++) {
            switch (between.charAt(i)) {
                case '(' -> parens++;
                case ')' -> {
                    if (--parens < 0) {
                        return false;
                    }
                }
                case ',' -> {
                    if (parens == 0) {
                        return false;
                    }
                }
                case ';', '{', '}' -> {
                    return false;
                }
                default -> {
                }
            }
        }
        return parens == 0;
    }

    /**
     * Annotations at the given brace depth within the range (0 = on a top-level type, 1 = on its members).
     * Annotations nested in another annotation's arguments are skipped with those arguments.
     */
    List<Annotation> annotations(int start, int end, int braceDepth) {
        List<Annotation> found = new ArrayList<>();
        int i = start;
        while (i < end) {
            Annotation annotation = code.charAt(i) == '@' && depth[i] == braceDepth ? annotationAt(i) : null;
            if (annotation == null) {
                i++;
                continue;
            }
            if (!annotation.name().equals("interface")) {
                found.add(annotation);
            }
            i = annotation.end();
        }
        return found;
    }

    /** Attributes of an annotation; a lone unnamed argument is reported as {@code value}. */
    List<Attribute> attributes(Annotation annotation) {
        if (annotation.args() == null) {
            return List.of();
        }
        List<Span> parts = splitTopLevel(annotation.args());
        List<Attribute> attributes = new ArrayList<>();
        for (Span part : parts) {
            Matcher named = ATTRIBUTE_NAME.matcher(code).region(part.start(), part.end());
            if (named.lookingAt()) {
                attributes.add(new Attribute(named.group(1), new Span(named.end(), part.end())));
            } else if (parts.size() == 1 && !text(part).isBlank()) {
                attributes.add(new Attribute("value", part));
            }
        }
        return attributes;
    }

    /**
     * The values of an annotation attribute: one per array element, or a single one. Operands of a
     * {@code +} chain are joined: string literals contribute their content, anything else (typically a
     * constant) is not resolved and contributes its source text, so {@code Paths.API + "/x"} yields
     * {@code Paths.API/x} rather than a plausible but wrong {@code /x}.
     */
    List<String> values(Span attributeValue) {
        int first = skipWhitespace(attributeValue.start());
        List<Span> elements = first < attributeValue.end() && code.charAt(first) == '{'
                ? splitTopLevel(new Span(first + 1, Math.min(closing(first, '{', '}'), attributeValue.end())))
                : List.of(attributeValue);
        List<String> values = new ArrayList<>();
        for (Span element : elements) {
            StringBuilder value = new StringBuilder();
            boolean hasLiteral = false;
            int at = element.start();
            for (Literal literal : literals) {
                if (literal.start() >= at && literal.start() < element.end()) {
                    value.append(operands(at, literal.start())).append(literal.value());
                    at = Math.min(literal.end(), element.end());
                    hasLiteral = true;
                }
            }
            value.append(operands(at, element.end()));
            // An empty literal is a real value ({"", "/x"} maps the prefix itself); an empty element is not.
            if (hasLiteral || !value.isEmpty()) {
                values.add(value.toString());
            }
        }
        return values;
    }

    private String operands(int start, int end) {
        return code.substring(start, end).replaceAll("[\\s+]", "");
    }

    /**
     * Name of the method declared right after {@code position}, skipping further annotations: the last
     * identifier before the first {@code (}. Empty when a field, type or anything else follows.
     */
    Optional<String> methodNameAfter(int position) {
        int i = skipWhitespace(position);
        while (i < code.length() && code.charAt(i) == '@') {
            Annotation annotation = annotationAt(i);
            if (annotation == null) {
                return Optional.empty();
            }
            i = skipWhitespace(annotation.end());
        }
        int paren = i;
        while (paren < code.length() && "(;{}=".indexOf(code.charAt(paren)) < 0) {
            paren++;
        }
        if (paren == code.length() || code.charAt(paren) != '(') {
            return Optional.empty();
        }
        int end = paren;
        while (end > i && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > i && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
            start--;
        }
        return start < end ? Optional.of(code.substring(start, end)) : Optional.empty();
    }

    private void findTypes(int headerEnd) {
        Matcher declaration = TYPE_DECL.matcher(code);
        int regionStart = headerEnd;
        int from = headerEnd;
        while (from < code.length() && declaration.find(from)) {
            from = declaration.end();
            if (depth[declaration.start()] != 0) {
                continue;
            }
            String keyword = declaration.group(1);
            if (keyword.equals("record") && !nextCharIsOneOf(declaration.end(), "(<")) {
                continue;
            }
            int bodyStart = typeBodyStart(declaration.end());
            if (bodyStart < 0) {
                continue;
            }
            int end = Math.min(code.length(), closing(bodyStart, '{', '}') + 1);
            String kind = keyword.startsWith("@") ? "annotation" : keyword;
            types.add(new TypeDecl(declaration.group(2), kind, regionStart, bodyStart, end,
                    annotations(regionStart, declaration.start(), 0)));
            regionStart = end;
            from = end;
        }
    }

    /** The type body's opening brace: the first '{' outside parentheses (record headers, annotations). */
    private int typeBodyStart(int from) {
        int parens = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens = Math.max(0, parens - 1);
            } else if (parens == 0 && c == '{') {
                return i;
            } else if (parens == 0 && c == ';') {
                return -1;
            }
        }
        return -1;
    }

    private Annotation annotationAt(int at) {
        Matcher name = ANNOTATION.matcher(code).region(at, code.length());
        if (!name.lookingAt()) {
            return null;
        }
        String qualified = withoutWhitespace(name.group(1));
        String simpleName = qualified.substring(qualified.lastIndexOf('.') + 1);
        int next = skipWhitespace(name.end());
        if (next < code.length() && code.charAt(next) == '(') {
            int close = closing(next, '(', ')');
            return new Annotation(simpleName, at, Math.min(code.length(), close + 1), new Span(next + 1, close));
        }
        return new Annotation(simpleName, at, name.end(), null);
    }

    /** Splits on commas outside nested parentheses and braces. */
    private List<Span> splitTopLevel(Span span) {
        List<Span> parts = new ArrayList<>();
        int nesting = 0;
        int from = span.start();
        for (int i = span.start(); i < span.end(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{') {
                nesting++;
            } else if (c == ')' || c == '}') {
                nesting--;
            } else if (c == ',' && nesting == 0) {
                parts.add(new Span(from, i));
                from = i + 1;
            }
        }
        parts.add(new Span(from, span.end()));
        return parts;
    }

    /** Index of the bracket closing the one at {@code open}, or the end of the code when unbalanced. */
    private int closing(int open, char opening, char closing) {
        int nesting = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == opening) {
                nesting++;
            } else if (c == closing && --nesting == 0) {
                return i;
            }
        }
        return code.length();
    }

    private boolean nextCharIsOneOf(int from, String chars) {
        int i = skipWhitespace(from);
        return i < code.length() && chars.indexOf(code.charAt(i)) >= 0;
    }

    private int skipWhitespace(int from) {
        int i = from;
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        return i;
    }

    /** depth[i] is the brace nesting before the character at i; stray closing braces do not go negative. */
    private static int[] braceDepths(String code) {
        int[] depths = new int[code.length() + 1];
        int current = 0;
        for (int i = 0; i < code.length(); i++) {
            depths[i] = current;
            char c = code.charAt(i);
            if (c == '{') {
                current++;
            } else if (c == '}') {
                current = Math.max(0, current - 1);
            }
        }
        depths[code.length()] = current;
        return depths;
    }

    /**
     * Index where the literal's content ends: at the closing delimiter, or for an unterminated literal at
     * the end of the line (single-line literals) or of the text.
     */
    private static int closingDelimiter(String text, int from, String delimiter, boolean multiLine) {
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (text.startsWith(delimiter, i) || (c == '\n' && !multiLine)) {
                return i;
            } else {
                i++;
            }
        }
        return text.length();
    }

    /** Blanks a range but keeps line breaks, so line structure and offsets survive. */
    private static void blank(char[] code, int start, int end) {
        for (int i = start; i < end; i++) {
            if (code[i] != '\n') {
                code[i] = ' ';
            }
        }
    }

    /** Common escapes only; octal and unicode escapes are left as they are. */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 == raw.length()) {
                out.append(c);
                continue;
            }
            char escaped = raw.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 's' -> out.append(' ');
                case '\n' -> { } // text-block line continuation
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    private static String withoutWhitespace(String text) {
        return text.replaceAll("\\s+", "");
    }
}
