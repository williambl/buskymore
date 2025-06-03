package com.williambl.buskymore;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.williambl.buskymore.PostFilter.Fisp.arr;
import static com.williambl.buskymore.PostFilter.Fisp.str;

@FunctionalInterface
public interface PostFilter extends Predicate<Post> {
    Functions FUNCTIONS = new Functions();
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

            public <F extends Fisp> Stream<F> argStream(Class<F> clazz) {
                return this.values.stream()
                        .skip(1)
                        .filter(clazz::isInstance)
                        .map(clazz::cast);
            }

            public <F extends Fisp> List<F> arguments(Class<F> clazz) {
                return this.argStream(clazz).toList();
            }


            @Override
            public String toString() {
                return print(this);
            }
        }

        static Fisp.Array arr(Fisp... values) {
            return new Array(List.of(values));
        }

        static Fisp.Str str(String value) {
            return new Str(value);
        }

        static Fisp.Bool bool(boolean value) {
            return new Bool(value);
        }
    }

    @FunctionalInterface
    interface FilterType {
        PostFilter create(Fisp.Array fisp, Functions functions, Map<String, Object> context);
        static FilterType unit(PostFilter instance) {
            return (fisp, functions, context) -> instance;
        }
        static FilterType transf(FilterTypeTransformer transformer) {
            return (fisp, functions, context) -> functions.build(transformer.apply(fisp, functions, context), context);
        }
        static FilterType replace(Fisp.Array newExp) {
            return (fisp, functions, context) -> functions.build(newExp, context);
        }
    }

    @FunctionalInterface
    interface FilterTypeTransformer {
        Fisp.Array apply(Fisp.Array fisp, Functions functions, Map<String, Object> context);
    }

    final class Functions {
        private final Map<String, FilterType> filterTypes = new HashMap<>();

        public void register(String name, FilterType filterType, String... aliases) {
            this.filterTypes.put(name, filterType);
            for (String alias : aliases) {
                this.filterTypes.put(alias, filterType);
            }
        }

        public FilterType get(String name) {
            var type = this.filterTypes.get(name);
            if (type == null) {
                throw new NoSuchElementException("No such filter type %s".formatted(name));
            }

            return type;
        }

        public PostFilter build(Fisp fisp, Map<String, Object> context) {
            switch (fisp) {
                case Fisp.Bool(boolean value) -> {
                    return value ? PostFilter.TRUE : PostFilter.FALSE;
                }
                case Fisp.Str s -> {
                    return this.build(new Fisp.Array(List.of(s)), context);
                }
                case Fisp.Array(var values) when values.isEmpty() -> {
                    return PostFilter.TRUE;
                }
                case Fisp.Array a when a.values.getFirst() instanceof Fisp.Str(String value) -> {
                    var type = this.get(value);
                    return type.create(a, this, context);
                }
                default -> {
                    throw new IllegalArgumentException("I don't know how to build a filter out of this -> "+fisp);
                }
            }
        }

        public List<PostFilter> build(List<Fisp> fisps, Map<String, Object> context) {
            return fisps.stream().map(f -> this.build(f, context)).toList();
        }
    }

    static void bootstrap() {
        FUNCTIONS.register("all_of", (fisp, functions, ctx) -> {
            var args = functions.build(fisp.arguments(), ctx);
            return post -> args.stream().allMatch(p -> p.test(post));
        }, "all", "and");
        FUNCTIONS.register("any_of", (fisp, functions, ctx) -> {
            var args = functions.build(fisp.arguments(), ctx);
            return post -> args.stream().anyMatch(p -> p.test(post));
        }, "any", "or", "either");
        FUNCTIONS.register("not", (fisp, functions, ctx) -> {
            var arg = functions.build(fisp.argument(), ctx);
            return post -> !arg.test(post);
        }, "!");
        FUNCTIONS.register("has_embed", FilterType.unit(Post::hasEmbeds));
        FUNCTIONS.register("reason_is", (fisp, functions, context) -> {
            Set<String> values = fisp.argStream(Fisp.Str.class).map(Fisp.Str::value).collect(Collectors.toSet());
            return post -> post.reason().filter(values::contains).isPresent();
        });
        FUNCTIONS.register("is_retweet", FilterType.transf((fisp, functions, context) ->
                arr(str("reason_is"), str("app.bsky.feed.defs#reasonRepost"))));
        FUNCTIONS.register("author_is", (fisp, functions, context) -> {
            Set<String> values = fisp.argStream(Fisp.Str.class).map(Fisp.Str::value).collect(Collectors.toSet());
            return post -> values.contains(post.authorDid());
        });
        FUNCTIONS.register("is_authored_by_self", FilterType.transf((fisp, functions, context) -> {
            String userDid = context.get("userDid") instanceof String str ? str : "";
            return arr(str("author_is"), str(userDid));
        }));
        FUNCTIONS.register("is_self_retweet", FilterType.replace(
                Fisp.parse("either (not is_retweet) (is_authored_by_self)")));
        FUNCTIONS.register("labels_contains", (fisp, functions, context) -> {
            Set<String> values = fisp.argStream(Fisp.Str.class).map(Fisp.Str::value).collect(Collectors.toSet());
            return post -> post.labels().stream().anyMatch(values::contains);
        });
        FUNCTIONS.register("contains_regex", (fisp, functions, ctx) -> {
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
