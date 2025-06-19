package com.williambl.buskymore;

import com.google.gson.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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

            return atom(value);
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
                case Atom a -> {
                    String str = a.value;
                    if (str.codePoints().anyMatch(c ->
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

        final class Atom implements Fisp {

            static final String DIGITS_REGEX = "(\\p{Digit}+)";
            static final String HEX_DIGITS_REGEX = "(\\p{XDigit}+)";
            static final String EXP_REGEX = "[eE][+-]?"+ DIGITS_REGEX;
            static final String DOUBLE_REGEX = ("[\\x00-\\x20]*[+-]?(NaN|Infinity|(((" + DIGITS_REGEX + "(\\.)?(" + DIGITS_REGEX + "?)(" + EXP_REGEX + ")?)|(\\.(" + DIGITS_REGEX + ")(" + EXP_REGEX + ")?)|(((0[xX]" + HEX_DIGITS_REGEX + "(\\.)?)|(0[xX]" + HEX_DIGITS_REGEX + "?(\\.)" + HEX_DIGITS_REGEX + "))[pP][+-]?" + DIGITS_REGEX + "))[fFdD]?))[\\x00-\\x20]*");
            private final String value;
            private final boolean canBeBoolean;
            private final boolean booleanValue;
            private final boolean canBeDouble;
            private final double doubleValue;
            private final boolean canBeInteger;
            private final int integerValue;

            public Atom(String value) {
                this.value = value;
                if (value.equals("true") || value.equals("false")) {
                    this.canBeBoolean = true;
                    this.booleanValue = value.equals("true");
                    this.canBeDouble = false;
                    this.doubleValue = 0;
                    this.canBeInteger = false;
                    this.integerValue = 0;
                } else {
                    this.canBeBoolean = false;
                    this.booleanValue = false;
                    if (Pattern.matches(DOUBLE_REGEX, value)) {
                        this.canBeDouble = true;
                        this.doubleValue = Double.parseDouble(value);
                        if (Math.floor(this.doubleValue) == this.doubleValue) {
                            this.canBeInteger = true;
                            this.integerValue = (int) this.doubleValue;
                        } else {
                            this.canBeInteger = false;
                            this.integerValue = 0;
                        }
                    } else {
                        this.canBeDouble = false;
                        this.canBeInteger = false;
                        this.doubleValue = 0;
                        this.integerValue = 0;
                    }
                }
            }

            public String value() {
                return this.value;
            }

            public boolean canBeBoolean() {
                return this.canBeBoolean;
            }
            public boolean canBeDouble() {
                return this.canBeDouble;
            }
            public boolean canBeInteger() {
                return this.canBeInteger;
            }
            public boolean asBoolean() {
                return this.booleanValue;
            }
            public double asDouble() {
                return this.doubleValue;
            }
            public int asInteger() {
                return this.integerValue;
            }

            @Override
            public int hashCode() {
                return Objects.hash(this.value);
            }

            @Override
            public boolean equals(Object obj) {
                return obj instanceof Atom a && a.value.equals(this.value);
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

        static Atom str(String value) {
            return new Atom(value);
        }

        static Atom atom(boolean value) {
            return new Atom(Boolean.toString(value));
        }

        static Atom atom(String value) {
            return new Atom(value);
        }

        static boolean isTruthy(Fisp fisp) {
            return switch (fisp) {
                case Array(List<Fisp> values) when values.isEmpty() -> false;
                case Array(List<Fisp> values) when values.size() == 1 -> isTruthy(values.getFirst());
                case Array(List<Fisp> values) -> true;
                case Atom atom when atom.canBeBoolean() -> atom.asBoolean();
                case Atom atom when atom.canBeDouble() -> atom.asDouble() != 0;
                case Atom atom -> !atom.value.isBlank();
            };
        }

        static Fisp fromJson(JsonElement jsonElement) {
            return switch (jsonElement) {
                case JsonObject obj -> new Array(obj.entrySet().stream().<Fisp>map(e -> arr(str(e.getKey()), fromJson(e.getValue()))).toList());
                case JsonArray arr -> new Array(arr.asList().stream().map(Fisp::fromJson).toList());
                case JsonPrimitive prim -> str(prim.getAsString());
                default -> str(""); // null
            };
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
        FispFunc EVAL = (fisp, functions, context) -> functions.eval(fisp, context);

        Fisp apply(Array fisp, Functions functions, FilterContext context);

        @FunctionalInterface
        interface ToBool {
            boolean apply(Array fisp, Functions functions, FilterContext context);
        }
        @FunctionalInterface
        interface ToStr {
            String apply(Array fisp, Functions functions, FilterContext context);
        }
        @FunctionalInterface
        interface ToArray {
            List<Fisp> apply(Array fisp, Functions functions, FilterContext context);
        }
        static FispFunc filter(ToBool func) {
            return (fisp, functions, context) -> atom(func.apply(fisp, functions, context));
        }
        static FispFunc postFilter(PostFilter filter) {
            return (fisp, functions, context) -> atom(filter.test(context));
        }
        static FispFunc toStr(ToStr func) {
            return (fisp, functions, context) -> atom(func.apply(fisp, functions, context));
        }
        static FispFunc transf(ToArray transformer) {
            return (fisp, functions, context) -> functions.eval(new Array(transformer.apply(fisp, functions, context)), context);
        }
        static FispFunc replace(Array newExp) {
            return (fisp, functions, context) -> functions.eval(newExp, context);
        }
        static FispFunc of(ToArray transformer) {
            return (fisp, functions, context) -> new Array(transformer.apply(fisp, functions, context));
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

        private boolean has(String funcName) {
            return this.filterTypes.containsKey(funcName);
        }

        public FispFunc get(String name) {
            var type = this.filterTypes.get(name);
            if (type == null) {
                throw new NoSuchElementException("No such filter type %s".formatted(name));
            }

            return type;
        }

        public Optional<FispFunc> maybeGet(String name) {
            return Optional.ofNullable(this.filterTypes.get(name));
        }

        public boolean evalToBool(Fisp fisp, FilterContext context) {
            return isTruthy(this.evalToAtom(fisp, context));
        }

        public String evalToString(Fisp fisp, FilterContext context) {
            return this.evalToAtom(fisp, context).value();
        }

        public Atom evalToAtom(Fisp fisp, FilterContext context) {
            return switch (fisp) {
                case Array a when a.values.getFirst() instanceof Atom funcName -> {
                    var type = this.get(funcName.value());
                    yield switch (type.apply(a, this, context)) {
                        case Array array -> this.evalToAtom(array, context);
                        case Atom atom -> atom;
                    };
                }
                case Array a when a.values.size() == 1 -> this.evalToAtom(a.values.getFirst(), context);
                case Atom a -> a;
                default -> throw new IllegalArgumentException("Cannot evaluate FISP expression %s to an atom!".formatted(print(fisp)));
            };
        }

        public Fisp eval(Fisp fisp, FilterContext context) {
            return switch (fisp) {
                case Array a when a.values.getFirst() instanceof Atom atom -> {
                    var type = this.get(atom.value());
                    yield  type.apply(a, this, context);
                }
                default -> fisp;
            };
        }

        public PostFilter build(Fisp fisp) {
            return filterContext -> isTruthy(this.eval(fisp, filterContext));
        }
    }

    static void bootstrap() {
        FUNCTIONS.register("all_of", FispFunc.filter((fisp, functions, ctx) ->
                        fisp.argStream().allMatch(f -> functions.evalToBool(f, ctx))),
                "all", "and");
        FUNCTIONS.register("any_of", FispFunc.filter((fisp, functions, ctx) ->
                        fisp.argStream().anyMatch(f -> functions.evalToBool(f, ctx))),
                "any", "or", "either");
        FUNCTIONS.register("not", FispFunc.filter((fisp, functions, ctx) ->
                        !functions.evalToBool(fisp.argument(), ctx)),
                "!");
        FUNCTIONS.register("extract", FispFunc.of((fisp, functions, context) -> {
            List<JsonPath.Segment> segments = new ArrayList<>();
            for (Iterator<Fisp> iterator = fisp.arguments().iterator(); iterator.hasNext(); ) {
                var unevaledArg = iterator.next();
                var evaledArg = functions.evalToAtom(unevaledArg, context);
                if (segments.isEmpty() && evaledArg.value().equals("$")) {
                    continue;
                }
                if (evaledArg.value().equals(".")) {
                    Fisp next = iterator.next();
                    List<JsonPath.Selector> selectors = makeJsonPathSelectorsFromFisp(next, functions, context);
                    segments.add(new JsonPath.Segment.ChildSegment(selectors));
                } else if (evaledArg.value().equals("..")) {
                    Fisp next = iterator.next();
                    List<JsonPath.Selector> selectors = makeJsonPathSelectorsFromFisp(next, functions, context);
                    segments.add(new JsonPath.Segment.ChildSegment(selectors));
                } else {
                    //TODO think about errors.
                    continue;
                }
            }
            return new JsonPath(segments).select(context.post().json()).map(Fisp::fromJson).toList();
        }));
        FUNCTIONS.register("has_embed", FispFunc.postFilter(p -> p.post().hasEmbeds()));
        FUNCTIONS.register("reason_is", FispFunc.filter((fisp, functions, context) ->
                context.post().reason().filter(r ->
                                fisp.argStream()
                                        .map(a -> functions.evalToString(a, context))
                                        .anyMatch(r::equals))
                        .isPresent()));
        FUNCTIONS.register("is_retweet", FispFunc.replace(
                arr(str("reason_is"), str("app.bsky.feed.defs#reasonRepost"))));
        FUNCTIONS.register("author_is", FispFunc.filter((fisp, functions, context) ->
                Optional.of(context.post().authorDid()).filter(r ->
                                fisp.argStream()
                                        .map(a -> functions.evalToString(a, context))
                                        .anyMatch(r::equals))
                        .isPresent()));
        FUNCTIONS.register("is_authored_by_self", FispFunc.transf((fisp, functions, context) ->
                List.of(str("author_is"), context instanceof FilterContext.WithUser wu ? str(wu.userDid()) : str(""))));
        FUNCTIONS.register("is_self_retweet", FispFunc.replace(
                parse("either (not is_retweet) (is_authored_by_self)")));
        FUNCTIONS.register("labels_contains", FispFunc.filter((fisp, functions, context) ->
                context.post().labels().stream().anyMatch(r ->
                        fisp.argStream()
                                .map(a -> functions.evalToString(a, context))
                                .anyMatch(r::equals))));
        FUNCTIONS.register("contains_regex", FispFunc.filter((fisp, functions, ctx) -> {
            var arg = functions.evalToString(fisp.argument(), ctx);
            var pattern = Pattern.compile(arg);
            return pattern.matcher(ctx.post().text()).find(0);
        }));
    }

    Pattern SLICE_PATTERN = Pattern.compile("(-?\\d+)?:(-?\\d+)?:(-?\\d+)?");

    static List<JsonPath.Selector> makeJsonPathSelectorsFromFisp(Fisp next, Functions functions, FilterContext ctx) {
        return switch (next) {
            case Atom a when a.value.equals("*") -> List.of(JsonPath.all());
            case Atom a when a.canBeInteger() -> List.of(JsonPath.idx(a.asInteger()));
            case Atom a -> {
                var matcher = SLICE_PATTERN.matcher(a.value());
                if (matcher.find()) {
                    yield List.of(JsonPath.slice(
                            matcher.group(1).isEmpty() ? null : Integer.parseInt(matcher.group(1)),
                            matcher.group(2).isEmpty() ? null : Integer.parseInt(matcher.group(2)),
                            matcher.group(3).isEmpty() ? null : Integer.parseInt(matcher.group(3))
                    ));
                } else {
                    yield List.of(JsonPath.name(a.value()));
                }
            }
            case Array(List<Fisp> values) when values.size() == 2 && values.getFirst().equals(str("name")) ->
                    List.of(JsonPath.name(functions.evalToString(values.get(1), ctx)));
            case Array(List<Fisp> values) ->
                    values.stream().flatMap(v -> makeJsonPathSelectorsFromFisp(v, functions, ctx).stream()).toList();
        };
    }
}
