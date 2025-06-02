package com.williambl.buskymore;

import java.text.NumberFormat;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@FunctionalInterface
public interface PostFilter extends Predicate<Post> {
    Parser PARSER = new Parser();
    PostFilter TRUE = $ -> true;
    PostFilter FALSE = $ -> false;

    // fisp (filter lisp)
    sealed interface Fisp {
        record Bool(boolean value) implements Fisp {
            @Override
            public String toString() {
                return Boolean.toString(this.value);
            }
        }
        record Str(String value) implements Fisp {
            @Override
            public String toString() {
                StringBuilder res = new StringBuilder();
                this.value.codePoints().forEachOrdered(codepoint -> {
                    if (codepoint == '\"') {
                        res.append("\"");
                    } else if (codepoint == '\\') {
                        res.append("\\\\");
                    } else if (codepoint == '\b') {
                        res.append("\\b");
                    } else if (codepoint == '\f') {
                        res.append("\\f");
                    } else if (codepoint == '\n') {
                        res.append("\\n");
                    } else if (codepoint == '\r') {
                        res.append("\\r");
                    } else if (codepoint == '\t') {
                        res.append("\\t");
                    } else if (Character.isISOControl(codepoint) || !Character.isDefined(codepoint)) {
                        res.append("\\u").append("%04x".formatted(codepoint).toUpperCase(Locale.ROOT));
                    } else {
                        res.appendCodePoint(codepoint);
                    }
                });
                return res.toString();
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
                if (this.values.isEmpty()) {
                    return "()";
                }

                if (this.values.size() == 1) {
                    return "(" + this.values.getFirst().toString() + ")";
                }

                StringBuilder result = new StringBuilder();
                boolean shouldUseNewlines = false;
                int[] whitespaces = new int[this.values.size() + 1];
                result.append("( ");
                whitespaces[0] = result.length() - 1;
                List<Fisp> fisps = this.values;
                for (int i = 0; i < fisps.size(); i++) {
                    var value = fisps.get(i);
                    String valueAsString = value.toString();
                    result.append(valueAsString);
                    result.append(' ');
                    whitespaces[i + 1] = result.length() - 1;
                    if (valueAsString.contains("\n")) {
                        shouldUseNewlines = true;
                    }
                }
                result.append(")");
                if (result.length() > 80) {
                    shouldUseNewlines = true;
                }
                if (shouldUseNewlines) {
                    for (int idx : whitespaces) {
                        result.setCharAt(idx, '\n');
                    }
                }
                return result.toString();
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
        PARSER.register("and", (fisp, parser) -> {
            var args = parser.parse(fisp.arguments());
            return post -> args.stream().allMatch(p -> p.test(post));
        });
        PARSER.register("or", (fisp, parser) -> {
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
