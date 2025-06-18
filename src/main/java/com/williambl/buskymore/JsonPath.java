package com.williambl.buskymore;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public record JsonPath(List<Segment> segments) {
    public static JsonPath of(Segment... segments) {
        return new JsonPath(Arrays.asList(segments));
    }

    public static Segment.ChildSegment dot(Selector... selectors) {
        return new Segment.ChildSegment(Arrays.asList(selectors));
    }

    public static Segment.ChildSegment dot(String name) {
        return dot(name(name));
    }

    public static Segment.ChildSegment dot(int idx) {
        return dot(idx(idx));
    }

    public static Segment.DescendantSegment dotdot(Selector... selectors) {
        return new Segment.DescendantSegment(Arrays.asList(selectors));
    }

    public static Selector.NameSelector name(String name) {
        return new Selector.NameSelector(name);
    }

    public static Selector.WildcardSelector all() {
        return new Selector.WildcardSelector();
    }

    public static Selector.IndexSelector idx(int idx) {
        return new Selector.IndexSelector(idx);
    }

    public static Selector.SliceSelector slice(Integer start, Integer end) {
        return slice(start, end, null);
    }

    public static Selector.SliceSelector slice(Integer start, Integer end, Integer step) {
        return new Selector.SliceSelector(start, end, step);
    }

    public Stream<JsonElement> select(JsonElement root) {
        Stream<JsonElement> stream = Stream.of(root);
        for (var segment : this.segments) {
            stream = segment.select(stream);
        }
        return stream;
    }

    public sealed interface Segment {
        record ChildSegment(List<Selector> selectors) implements Segment {
            @Override
            public Stream<JsonElement> select(Stream<JsonElement> elements) {
                return elements
                        .flatMap(j -> this.selectors.stream().flatMap(sel -> sel.select(j)));
            }
        }
        record DescendantSegment(List<Selector> selectors) implements Segment {
            @Override
            public Stream<JsonElement> select(Stream<JsonElement> elements) {
                return elements.flatMap(JsonPath::getDescendentsAndSelf)
                        .flatMap(j -> this.selectors.stream().flatMap(sel -> sel.select(j)));
            }
        }

        Stream<JsonElement> select(Stream<JsonElement> elements);
    }

    private static Stream<JsonElement> getChildrenOrEmpty(JsonElement e) {
        if (e instanceof JsonArray arr) {
            return arr.asList().stream();
        } else if (e instanceof JsonObject obj) {
            return obj.asMap().values().stream();
        } else {
            return Stream.empty();
        }
    }

    private static Stream<JsonElement> getDescendentsAndSelf(JsonElement e) {
        var selfStream = Stream.of(e);
        if (e instanceof JsonArray arr) {
            return Stream.concat(selfStream, arr.asList().stream().flatMap(JsonPath::getDescendentsAndSelf));
        } else if (e instanceof JsonObject obj) {
            return Stream.concat(selfStream, obj.asMap().values().stream().flatMap(JsonPath::getDescendentsAndSelf));
        } else {
            return selfStream;
        }
    }

    private static Stream<JsonElement> getDescendents(JsonElement e) {
        if (e instanceof JsonArray arr) {
            return arr.asList().stream().flatMap(JsonPath::getDescendentsAndSelf);
        } else if (e instanceof JsonObject obj) {
            return obj.asMap().values().stream().flatMap(JsonPath::getDescendentsAndSelf);
        } else {
            return Stream.of(e);
        }
    }

    public sealed interface Selector {
        record NameSelector(String name) implements Selector {
            @Override
            public Stream<JsonElement> select(JsonElement element) {
                return element instanceof JsonObject o
                        ? Optional.ofNullable(o.get(this.name)).stream()
                        : Stream.empty();
            }
        }
        record WildcardSelector() implements Selector {
            @Override
            public Stream<JsonElement> select(JsonElement element) {
                return getChildrenOrEmpty(element);
            }
        }
        record IndexSelector(int index) implements Selector {
            @Override
            public Stream<JsonElement> select(JsonElement element) {
                return element instanceof JsonArray a && a.size() > this.index
                        ? Stream.of(a.get(this.index))
                        : Stream.empty();
            }
        }
        record SliceSelector(OptionalInt start, OptionalInt end, OptionalInt step) implements Selector {
            public SliceSelector(Integer start, Integer end, Integer step) {
                this(
                        start == null ? OptionalInt.empty() : OptionalInt.of(start),
                        end == null ? OptionalInt.empty() : OptionalInt.of(end),
                        step == null ? OptionalInt.empty() : OptionalInt.of(step)
                );
            }

            // https://www.rfc-editor.org/rfc/rfc9535#section-2.3.4.2.2-5
            private static int normalise(int idx, SequencedCollection<?> seq) {
                return idx >= 0 ? idx : seq.size() - idx;
            }

            @Override
            public Stream<JsonElement> select(JsonElement element) {
                // https://www.rfc-editor.org/rfc/rfc9535#section-2.3.4.2.2-12

                if (element instanceof JsonArray a) {
                    var list = a.asList();

                    int step = this.step.orElse(1);
                    int start = this.start.orElseGet(() -> step >= 0 ? 0 : list.size() - 1);
                    int end = this.end.orElseGet(() -> step >= 0 ? list.size() : -list.size() - 1);

                    if (step == 0) {
                        return Stream.empty();
                    }

                    int nStart = normalise(start, list);
                    int nEnd = normalise(end, list);
                    // https://www.rfc-editor.org/rfc/rfc9535#section-2.3.4.2.2-8
                    int lower;
                    int upper;
                    // https://www.rfc-editor.org/rfc/rfc9535#section-2.3.4.2.2-10
                    int initialIdx;
                    IntPredicate hasNext;
                    if (step > 0) {
                        lower = Math.min(Math.max(nStart, 0), list.size());
                        upper = Math.min(Math.max(nEnd, 0), list.size());
                        initialIdx = lower;
                        hasNext = i -> i < upper;
                    } else {
                        lower = Math.min(Math.max(nEnd, -1), list.size()-1);
                        upper = Math.min(Math.max(nStart, -1), list.size()-1);
                        initialIdx = upper;
                        hasNext = i -> lower < i;
                    }

                    // https://www.rfc-editor.org/rfc/rfc9535#section-2.3.4.2.2-10
                    return IntStream.iterate(initialIdx, hasNext, i -> i + step)
                            .mapToObj(list::get);
                } else {
                    return Stream.empty();
                }
            }
        }
        record FilterSelector() implements Selector {
            @Override
            public Stream<JsonElement> select(JsonElement element) {
                throw new UnsupportedOperationException("Filter selector is not yet implemented");
            }
        }

        Stream<JsonElement> select(JsonElement element);
    }
}

