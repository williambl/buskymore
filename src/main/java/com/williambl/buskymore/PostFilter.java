package com.williambl.buskymore;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.williambl.buskymore.PostFilter.Fisp.*;

@FunctionalInterface
public interface PostFilter extends Predicate<PostFilter.FilterContext> {
    Functions FUNCTIONS = new Functions();
    PostFilter TRUE = $ -> true;
    PostFilter FALSE = $ -> false;

    // fisp (filter lisp)
    sealed interface Fisp {
        static Array parse(String input) {
            int[] cursor = new int[] {0};
            return parseList(input, cursor);
        }

        static Array parseList(String input, int[] cursor) {
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

        static Fisp parseAtom(String input, int[] cursor) {
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

        static void printInternal(Fisp value, StringBuilder sb, boolean isRoot) {
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
                return this.values.size() <= 1 ? new Array(List.of()) : this.values.get(1);
            }

            public List<Fisp> arguments() {
                return this.values.subList(1, this.values.size());
            }

            public Stream<Fisp> argStream() {
                return this.values.stream()
                        .skip(1);
            }

            public <F extends Fisp> Stream<F> argStream(Class<F> clazz) {
                return this.argStream()
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

        static Array arr(Fisp... values) {
            return new Array(List.of(values));
        }

        static Str str(String value) {
            return new Str(value);
        }

        static Bool bool(boolean value) {
            return new Bool(value);
        }

        static boolean isTruthy(Fisp fisp) {
            return switch (fisp) {
                case Fisp.Array(List<Fisp> elements) -> elements.isEmpty();
                case Fisp.Bool(boolean value) -> value;
                case Fisp.Str(String value) -> !value.isBlank();
            };
        }

        @SuppressWarnings("unchecked")
        default <T extends Fisp> T cast(Class<T> clazz) {
            if (clazz == Bool.class) {
                return (T) bool(isTruthy(this));
            } else if (clazz == Str.class) {
                return (T) (this instanceof Str s ? s : str(print(this)));
            } else if (clazz == Array.class) {
                return (T) (this instanceof Array a ? a : arr(this));
            }
            throw new IncompatibleClassChangeError();
        }
    }

    interface FilterContext {
        Post post();

        interface WithUser {
            String userDid();
        }

        static FilterContext of(Post post) {
            return () -> post;
        }

        static FilterContext of(Post post, String userDid) {
            record Impl(Post post, String userDid) implements FilterContext, WithUser {}
            return new Impl(post, userDid);
        }
    }

    @FunctionalInterface
    interface FispFunc {
        static final FispFunc EVAL = (fisp, functions, context) -> functions.eval(fisp, context);

        Fisp apply(Fisp.Array fisp, Functions functions, FilterContext context);

        @FunctionalInterface
        interface ToBool {
            boolean apply(Fisp.Array fisp, Functions functions, FilterContext context);
        }
        @FunctionalInterface
        interface ToStr {
            String apply(Fisp.Array fisp, Functions functions, FilterContext context);
        }
        @FunctionalInterface
        interface ToArray {
            List<Fisp> apply(Fisp.Array fisp, Functions functions, FilterContext context);
        }
        static FispFunc filter(ToBool func) {
            return (fisp, functions, context) -> new Fisp.Bool(func.apply(fisp, functions, context));
        }
        static FispFunc postFilter(PostFilter filter) {
            return (fisp, functions, context) -> new Fisp.Bool(filter.test(context));
        }
        static FispFunc toStr(ToStr func) {
            return (fisp, functions, context) -> new Fisp.Str(func.apply(fisp, functions, context));
        }
        static FispFunc transf(ToArray transformer) {
            return (fisp, functions, context) -> functions.eval(new Fisp.Array(transformer.apply(fisp, functions, context)), context);
        }
        static FispFunc replace(Fisp.Array newExp) {
            return (fisp, functions, context) -> functions.eval(newExp, context);
        }
    }

    final class Functions {
        private final Map<String, FispFunc> filterTypes = new HashMap<>();

        public void register(String name, FispFunc fispFunc, String... aliases) {
            this.filterTypes.put(name, fispFunc);
            for (String alias : aliases) {
                this.filterTypes.put(alias, fispFunc);
            }
        }

        public FispFunc get(String name) {
            var type = this.filterTypes.get(name);
            if (type == null) {
                throw new NoSuchElementException("No such filter type %s".formatted(name));
            }

            return type;
        }

        public Fisp eval(Fisp fisp, FilterContext context) {
            return switch (fisp) {
                case Fisp.Str s -> this.eval(new Fisp.Array(List.of(s)), context);
                case Fisp.Array a when a.values.getFirst() instanceof Fisp.Str(String value) -> {
                    var type = this.get(value);
                    yield  type.apply(a, this, context);
                }
                default -> fisp;
            };
        }

        public PostFilter build(Fisp fisp) {
            return filterContext -> switch (this.eval(fisp, filterContext)) {
                case Fisp.Array(List<Fisp> elements) -> elements.isEmpty();
                case Fisp.Bool(boolean value) -> value;
                case Fisp.Str(String value) -> !value.isBlank();
            };
        }
    }

    static void bootstrap() {
        FUNCTIONS.register("all_of", FispFunc.filter((fisp, functions, ctx) ->
                        fisp.argStream().allMatch(f -> isTruthy(functions.eval(fisp, ctx)))),
                "all", "and");
        FUNCTIONS.register("any_of", FispFunc.filter((fisp, functions, ctx) ->
                        fisp.argStream().anyMatch(f -> isTruthy(functions.eval(fisp, ctx)))),
                "any", "or", "either");
        FUNCTIONS.register("not", FispFunc.filter((fisp, functions, ctx) ->
                        !isTruthy(functions.eval(fisp, ctx))),
                "!");
        FUNCTIONS.register("has_embed", FispFunc.postFilter(p -> p.post().hasEmbeds()));
        FUNCTIONS.register("reason_is", FispFunc.filter((fisp, functions, context) ->
                context.post().reason().filter(r ->
                                fisp.argStream()
                                        .map(a -> functions.eval(fisp, context).cast(Str.class).value())
                                        .anyMatch(r::equals))
                        .isPresent()));
        FUNCTIONS.register("is_retweet", FispFunc.replace(
                arr(str("reason_is"), str("app.bsky.feed.defs#reasonRepost"))));
        FUNCTIONS.register("author_is", FispFunc.filter((fisp, functions, context) ->
                Optional.of(context.post().authorDid()).filter(r ->
                                fisp.argStream()
                                        .map(a -> functions.eval(fisp, context).cast(Str.class).value())
                                        .anyMatch(r::equals))
                        .isPresent()));
        FUNCTIONS.register("is_authored_by_self", FispFunc.transf((fisp, functions, context) ->
                List.of(str("author_is"), context instanceof FilterContext.WithUser wu ? str(wu.userDid()) : str(""))));
        FUNCTIONS.register("is_self_retweet", FispFunc.replace(
                Fisp.parse("either (not is_retweet) (is_authored_by_self)")));
        FUNCTIONS.register("labels_contains", FispFunc.filter((fisp, functions, context) ->
                context.post().labels().stream().anyMatch(r ->
                        fisp.argStream()
                                .map(a -> functions.eval(fisp, context).cast(Str.class).value())
                                .anyMatch(r::equals))));
        FUNCTIONS.register("contains_regex", FispFunc.filter((fisp, functions, ctx) -> {
            var arg = fisp.argument().cast(Str.class).value();
            var pattern = Pattern.compile(arg);
            return pattern.matcher(ctx.post().text()).find(0);
        }));
    }
}
