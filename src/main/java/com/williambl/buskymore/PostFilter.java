package com.williambl.buskymore;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@FunctionalInterface
public interface PostFilter extends Predicate<Post> {
    Parser PARSER = new Parser();
    PostFilter TRUE = $ -> true;
    PostFilter FALSE = $ -> false;

    // fisp (filter lisp)
    sealed interface Fisp {
        static Fisp.Array parse(String input) {
            int[] cursor = new int[] {0};
            return parseList(input, cursor);
        }

        private static Fisp.Array parseList(String input, int[] cursor) {
            List<Fisp> contents = new ArrayList<>();
            while (cursor[0] < input.length()) {
                var charAt = input.charAt(cursor[0]);
                if (Character.isWhitespace(charAt)) {
                    cursor[0]++;
                    continue;
                } else if (charAt == '(') {
                    cursor[0]++;
                    contents.add(parseList(input, cursor));
                } else if (charAt == ')') {
                    cursor[0]++;
                    break;
                } else {
                    contents.add(parseAtom(input, cursor));
                }
            }
            return new Array(List.copyOf(contents));
        }

        private static Fisp parseAtom(String input, int[] cursor) {
            StringBuilder res = new StringBuilder(input.length());
            boolean isQuoted = input.charAt(cursor[0]) == '"';
            if (isQuoted) {
                cursor[0]++;
            }
            boolean isEscaped = false;
            while (cursor[0] < input.length()) {
                var charAt = input.charAt(cursor[0]);
                if (!isQuoted) {
                    if (Character.isWhitespace(charAt)
                            || charAt == '('
                            || charAt == ')'
                            || charAt == '"') {
                        break;
                    }
                } else {
                    if (isEscaped) {
                        if (charAt == 'u') {
                            int codepoint = Integer.parseInt(input.substring(cursor[0]+1, cursor[0]+5), 16);
                            res.appendCodePoint(codepoint);
                            cursor[0] += 4;
                            continue;
                        } else if (charAt == 'b') {
                            charAt = '\b';
                        } else if (charAt == 'f') {
                            charAt = '\f';
                        } else if (charAt == 'n') {
                            charAt = '\n';
                        } else if (charAt == 'r') {
                            charAt = '\r';
                        } else if (charAt == 't') {
                            charAt = '\t';
                        }
                    } else {
                        if (charAt == '"') {
                            cursor[0]++;
                            break;
                        }
                        if (charAt == '\\') {
                            isEscaped = true;
                        }
                    }
                }
                res.append(charAt);
                cursor[0]++;
            }

            String value = res.toString();
            if (!isQuoted) {
                if (value.equals("true")) {
                    return new Bool(true);
                } else if (value.equals("false")) {
                    return new Bool(false);
                }
            }

            return new Str(value);
        }

        static String print(Fisp expression) {
            var sb = new StringBuilder();
            if (expression instanceof Array) {
                printInternal(expression, sb, true);
            } else {
                printInternal(new Array(List.of(expression)), sb, true);
            }
            return sb.toString();
        }

        private static void printInternal(Fisp value, StringBuilder sb, boolean isRoot) {
            switch (value) {
                case Array(List<Fisp> elements) -> {
                    if (elements.isEmpty()) {
                        if (!isRoot) {
                            sb.append("()");
                        }
                        return;
                    }

                    if (elements.size() == 1) {
                        if (isRoot) {
                            sb.append("(");
                        }
                        printInternal(elements.getFirst(), sb, false);
                        if (isRoot) {
                            sb.append(")");
                        }
                        return;
                    }

                    int sizeBeforeList = sb.length();
                    boolean shouldUseNewlines = false;
                    int[] whitespaces = new int[elements.size() + 1];
                    if (!isRoot) {
                        sb.append("( ");
                    }
                    whitespaces[0] = sb.length() - 1;
                    for (int i = 0; i < elements.size(); i++) {
                        var element = elements.get(i);
                        int sizeBeforeElement = sb.length();
                        printInternal(element, sb, false);
                        sb.append(' ');
                        whitespaces[i + 1] = sb.length() - 1;
                        if (IntStream.range(sizeBeforeElement, sb.length()).map(sb::charAt).anyMatch(c -> c == '\n')) {
                            shouldUseNewlines = true;
                        }
                    }
                    if (!isRoot) {
                        sb.append(")");
                    }
                    if (sb.length() - sizeBeforeList > 80) {
                        shouldUseNewlines = true;
                    }
                    shouldUseNewlines = false; //todo need to be able to parse multiline expressions haha
                    //noinspection ConstantValue
                    if (shouldUseNewlines) {
                        for (int idx : whitespaces) {
                            sb.setCharAt(idx, '\n');
                        }
                    }
                }
                case Bool(boolean bool) -> {
                    sb.append(bool);
                }
                case Str(String str) -> {
                    if (str.equals("true") || str.equals("false")
                            || str.codePoints().anyMatch(c ->
                            c == '"'
                                    || c == '\\'
                                    || c == '\b'
                                    || c == '\f'
                                    || c == '\n'
                                    || c == '\r'
                                    || c == '\t'
                                    || Character.isISOControl(c) || !Character.isDefined(c))
                    ) {
                        sb.append('"');
                        str.codePoints().forEachOrdered(codepoint -> {
                            if (codepoint == '\"') {
                                sb.append("\"");
                            } else if (codepoint == '\\') {
                                sb.append("\\\\");
                            } else if (codepoint == '\b') {
                                sb.append("\\b");
                            } else if (codepoint == '\f') {
                                sb.append("\\f");
                            } else if (codepoint == '\n') {
                                sb.append("\\n");
                            } else if (codepoint == '\r') {
                                sb.append("\\r");
                            } else if (codepoint == '\t') {
                                sb.append("\\t");
                            } else if (Character.isISOControl(codepoint) || !Character.isDefined(codepoint)) {
                                sb.append("\\u").append("%04x".formatted(codepoint).toUpperCase(Locale.ROOT));
                            } else {
                                sb.appendCodePoint(codepoint);
                            }
                        });
                        sb.append('"');
                    } else {
                        sb.append(str);
                    }
                }
            }
        }

        record Bool(boolean value) implements Fisp {
            @Override
            public String toString() {
                return print(this);
            }
        }

        record Str(String value) implements Fisp {
            @Override
            public String toString() {
                return print(this);
            }
        }
        record Array(List<Fisp> values) implements Fisp {
            public Fisp argument() {
                return this.values.size() <= 1 ? new Fisp.Array(List.of()) : this.values.get(1);
            }

            public List<Fisp> arguments() {
                return this.values.subList(1, this.values.size());
            }

            @Override
            public String toString() {
                return print(this);
            }
        }
    }

    @FunctionalInterface
    interface FilterType {
        PostFilter create(Fisp.Array fisp, Parser parser);
    }

    final class Parser {
        private final Map<String, FilterType> filterTypes = new HashMap<>();

        public void register(String name, FilterType filterType) {
            this.filterTypes.put(name, filterType);
        }

        public FilterType getFilterType(String name) {
            var type = this.filterTypes.get(name);
            if (type == null) {
                throw new NoSuchElementException("No such filter type %s".formatted(name));
            }

            return type;
        }

        public PostFilter parse(Fisp fisp) {
            switch (fisp) {
                case Fisp.Bool(boolean value) -> {
                    return value ? PostFilter.TRUE : PostFilter.FALSE;
                }
                case Fisp.Str s -> {
                    return this.parse(new Fisp.Array(List.of(s)));
                }
                case Fisp.Array(var values) when values.isEmpty() -> {
                    return PostFilter.TRUE;
                }
                case Fisp.Array a when a.values.getFirst() instanceof Fisp.Str(String value) -> {
                    var type = this.getFilterType(value);
                    return type.create(a, this);
                }
                default -> {
                    throw new IllegalArgumentException("I don't know how to parse this -> "+fisp);
                }
            }
        }

        public List<PostFilter> parse(List<Fisp> fisps) {
            return fisps.stream().map(this::parse).toList();
        }
    }

    static void bootstrap() {
        PARSER.register("all_of", (fisp, parser) -> {
            var args = parser.parse(fisp.arguments());
            return post -> args.stream().allMatch(p -> p.test(post));
        });
        PARSER.register("any_of", (fisp, parser) -> {
            var args = parser.parse(fisp.arguments());
            return post -> args.stream().anyMatch(p -> p.test(post));
        });
        PARSER.register("not", (fisp, parser) -> {
            var arg = parser.parse(fisp.argument());
            return post -> !arg.test(post);
        });
        PARSER.register("has_embed", (fisp, parser) -> Post::hasEmbeds);
        PARSER.register("is_retweet", (fisp, parser) -> post -> post.reason().filter("app.bsky.feed.defs#reasonRepost"::equals).isEmpty());
        PARSER.register("contains_regex", (fisp, parser) -> {
            var args = fisp.arguments();
            String regex;
            if (args.isEmpty()) {
                regex = "";
            } else {
                switch (fisp.arguments().getFirst()) {
                    case Fisp.Array array -> {
                        throw new IllegalArgumentException("The first argument to 'contains_regex' should be a string, not an array -> "+fisp);
                    }
                    case Fisp.Bool bool -> {
                        throw new IllegalArgumentException("The first argument to 'contains_regex' should be a string, not a boolean -> "+fisp);
                    }
                    case Fisp.Str(String value) -> {
                        regex = value;
                    }
                }
            }
            var pattern = Pattern.compile(regex);
            return post -> pattern.matcher(post.text()).find(0);
        });
    }
}
