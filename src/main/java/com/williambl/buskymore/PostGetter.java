package com.williambl.buskymore;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PostGetter {
    public static final Logger LOGGER = LoggerFactory.getLogger(PostGetter.class);
    private static final Set<String> NOT_EMBEDS = Set.of("app.bsky.embed.external", "app.bsky.embed.record");

    public record Config(String userAgent, String tumblrApiKey, int backlogDays, int maxBacklogPosts, String statePath, List<PostSource> postSources) {

        sealed public interface PostSource {
            record BskyUser(String userDid, PostFilter.Fisp filter) implements PostSource {
                @Override
                public String uniqueKey() {
                    return this.userDid;
                }
            }
            record BskyFeed(String userDid, String feedKey, PostFilter.Fisp filter) implements PostSource {
                @Override
                public String uniqueKey() {
                    return "%s/app.bsky.feed.generator/%s".formatted(this.userDid, this.feedKey);
                }
            }

            record TumblrBlog(String blogId, PostFilter.Fisp filter) implements PostSource {
                @Override
                public String uniqueKey() {
                    return "tumblr/%s".formatted(this.blogId);
                }
            }

            String uniqueKey();
        }
    }

    public record State(Map<String, Instant> latestPostTimestamps) {}
    public record Result(State state, List<Post> posts) {}

    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Config config;
    private final Path statePath;

    public PostGetter(Config config, ExecutorService executor) {
        this.config = config;
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .build();
        this.statePath = Path.of(this.config.statePath);
    }

    public State readState() throws IOException {
        if (!Files.exists(this.statePath)) {
            return new State(Map.of());
        }

        return new State(Arrays.stream(Files.readString(this.statePath).split("\n"))
                .map(line -> {
                    var kv = line.split("\t");
                    if (kv.length == 2) {
                        return Map.entry(kv[0], Instant.parse(kv[1]));
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    public void writeState(State state) throws IOException {
        try (var writer = Files.newBufferedWriter(this.statePath, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (var entry : state.latestPostTimestamps().entrySet()) {
                writer.write(entry.getKey());
                writer.write("\t");
                writer.write(entry.getValue().toString());
                writer.write("\n");
            }
        }
    }

    public CompletableFuture<Result> run(State state) {
        return CompletableFuture.supplyAsync(() -> {
            List<PostStream> postStreams = new ArrayList<>();
            List<Config.PostSource> postSources = this.config.postSources;
            Set<Config.PostSource> newSources = new HashSet<>();
            for (var postSource : postSources) {
                if (!state.latestPostTimestamps.containsKey(postSource.uniqueKey())) {
                    newSources.add(postSource);
                }
                Instant latestPostTimestamp = state.latestPostTimestamps().getOrDefault(postSource.uniqueKey(), Instant.now().minus(this.config.backlogDays(), ChronoUnit.DAYS));
                int maxPostCount = newSources.contains(postSource) ? this.config.maxBacklogPosts() : Integer.MAX_VALUE;
                var filter = PostFilter.FUNCTIONS.build(switch (postSource) {
                    case Config.PostSource.BskyFeed feed -> feed.filter();
                    case Config.PostSource.BskyUser user -> user.filter();
                    case Config.PostSource.TumblrBlog blog -> blog.filter();
                });

                Function<Post, PostFilter.FilterContext> filterContextFactory = switch (postSource) {
                    case Config.PostSource.BskyFeed feed -> PostFilter.FilterContext::of;
                    case Config.PostSource.BskyUser user -> p -> PostFilter.FilterContext.of(p, user.userDid());
                    case Config.PostSource.TumblrBlog blog -> p -> PostFilter.FilterContext.of(p, blog.blogId());
                };

                var postStream = (switch (postSource) {
                    case Config.PostSource.BskyFeed feed -> this.getPosts(feed);
                    case Config.PostSource.BskyUser user -> this.getPosts(user);
                    case Config.PostSource.TumblrBlog blog -> this.getPosts(blog);
                })
                        .filter(p -> p.createdAt().isAfter(latestPostTimestamp))
                        .filter(p -> filter.test(filterContextFactory.apply(p)))
                        .newerThan(latestPostTimestamp)
                        .limit(maxPostCount)
                        .build();
                postStreams.add(postStream);
            }
            Map<String, Instant> latestPostTimestamps = new HashMap<>(state.latestPostTimestamps());
            List<Post> posts = new ArrayList<>();
            for (int i = 0; i < postStreams.size(); i++) {
                var source = postSources.get(i);
                var stream = postStreams.get(i);
                posts.addAll(stream.posts());
                var latest = stream.latest();
                latestPostTimestamps.compute(source.uniqueKey(), (k, oldLatest) -> oldLatest == null || oldLatest.isBefore(latest) ? latest : oldLatest);
            }
            posts.sort(Comparator.comparing(Post::createdAt));
            return new Result(new State(latestPostTimestamps), posts);
        }, this.executor);
    }

    private PostStreamBuilder getPosts(Config.PostSource.BskyFeed feed) {
        return this.bskyPostStreamBuilder(cursor -> {
            String atUri = "at://%s/app.bsky.feed.generator/%s".formatted(feed.userDid(), feed.feedKey());
            StringBuilder query = new StringBuilder("?feed=");
            query.append(URLEncoder.encode(atUri, StandardCharsets.UTF_8));
            if (cursor != null) {
                query.append("&cursor=");
                query.append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            }
            URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed" + query);
            return HttpRequest.newBuilder(uri)
                    .headers(this.makeHeaders())
                    .GET()
                    .build();
        }, feed.userDid() + "/" + feed.feedKey());
    }

    private PostStreamBuilder getPosts(Config.PostSource.BskyUser user) {
        return this.bskyPostStreamBuilder(cursor -> {
            StringBuilder query = new StringBuilder("?actor=");
            query.append(URLEncoder.encode(user.userDid(), StandardCharsets.UTF_8));
            if (cursor != null) {
                query.append("&cursor=");
                query.append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            }
            URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed" + query);
            return HttpRequest.newBuilder(uri)
                    .headers(this.makeHeaders())
                    .GET()
                    .build();
        }, user.userDid());
    }

    private PostStreamBuilder getPosts(Config.PostSource.TumblrBlog blog) {
        return new PostStreamBuilder() {
            @Override
            public String sourceName() {
                return blog.blogId();
            }

            @Override
            public CompletableFuture<Optional<JsonElement>> getFeed(String cursor) {
                StringBuilder query = new StringBuilder("?api_key=");
                query.append(URLEncoder.encode(PostGetter.this.config.tumblrApiKey, StandardCharsets.UTF_8));
                query.append("&npf=true");
                query.append("&limit=20");
                if (cursor != null) {
                    query.append("&offset=");
                    query.append(URLEncoder.encode(cursor, StandardCharsets.UTF_8));
                }
                URI uri = URI.create("https://api.tumblr.com/v2/blog/%s/posts%s".formatted(blog.blogId(), query));
                var request = HttpRequest.newBuilder(uri)
                        .headers(PostGetter.this.makeHeaders())
                        .GET()
                        .build();
                var bodyHandler = jsonBodyHandler(request.uri());
                return PostGetter.this.httpClient.sendAsync(request, bodyHandler)
                        .thenApply(HttpResponse::body);
            }

            @Override
            protected String getCursor(JsonObject resObj, String oldCursor) {
                int cursorInt;
                try {
                    cursorInt = oldCursor == null ? 0 : Integer.parseInt(oldCursor);
                } catch (NumberFormatException e) {
                    cursorInt = 0;
                }
                return Integer.toString(cursorInt + 20);
            }

            @Override
            protected Optional<PostFeedResponse> getPosts(JsonObject response) {
                var posts = Optional.of(response)
                        .map(r -> r.get("response"))
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject)
                        .map(r -> r.get("posts"))
                        .filter(JsonElement::isJsonArray)
                        .map(JsonElement::getAsJsonArray)
                        .map(JsonArray::asList);
                if (posts.isEmpty()) {
                    LOGGER.warn("Got no posts array in response from {}, skipping: {}", this.sourceName(), response);
                    return Optional.empty();
                }

                return Optional.of(new PostFeedResponse(posts.get().size(),
                        posts.stream()
                                .flatMap(List::stream)
                                .filter(JsonObject.class::isInstance)
                                .map(JsonObject.class::cast)
                                .map(this::parsePost)));
            }

            private Post parsePost(JsonObject j) {
                try {
                    var uri = new URI(j.get("post_url").getAsString());
                    var authorUuid = j.getAsJsonObject("blog").get("uuid").getAsString();
                    var blocks = j.getAsJsonArray("content").asList().stream()
                            .filter(JsonElement::isJsonObject)
                            .map(JsonElement::getAsJsonObject)
                            .toList();
                    var text = blocks.stream()
                            .filter(jj -> jj.has("type") && jj.get("type").getAsString().equals("text"))
                            .map(jj -> jj.get("text").getAsString())
                            .collect(Collectors.joining());
                    var createdAt = Instant.ofEpochSecond(j.get("timestamp").getAsLong());
                    boolean hasEmbeds = blocks.stream()
                            .anyMatch(jj -> !jj.get("type").getAsString().equals("text"));
                    boolean hasVideos = blocks.stream()
                            .anyMatch(jj -> jj.get("type").getAsString().equals("video"));
                    return new Post(
                            uri,
                            authorUuid,
                            text,
                            createdAt,
                            Optional.empty(),
                            hasEmbeds,
                            hasVideos,
                            Set.of(),
                            j
                            );
                } catch (URISyntaxException | JsonParseException | NullPointerException e) {
                    LOGGER.error("Can't parse a post, ignoring it: {}", j, e);
                    return null;
                }
            }
        };
    }

    private static HttpResponse.BodyHandler<Optional<JsonElement>> jsonBodyHandler(URI uri) {
        return MoreBodyHandlers.decoding(responseInfo -> {
            HttpResponse.BodySubscriber<String> string = HttpResponse.BodyHandlers.ofString().apply(responseInfo);
            if (responseInfo.statusCode() / 100 != 2) {
                return HttpResponse.BodySubscribers.mapping(
                        string,
                        str -> {
                            LOGGER.error("From: {}, received non-OK status code: {}\nWith body: {}", uri, responseInfo.statusCode(), str);
                            return Optional.empty();//DataResult.error(() -> "Received non-OK status code %s (with body %s)".formatted(responseInfo.statusCode(), str));
                        });
            }

            return HttpResponse.BodySubscribers.mapping(
                    string,
                    s -> {
                        try {
                            LOGGER.trace("From: {}, received {}", uri, s);
                            return Optional.of(JsonParser.parseString(s));
                        } catch (JsonParseException e) {
                            LOGGER.error("From: {}, received invalid JSON {}: ", uri, s, e);
                            return Optional.empty();
                        }
                    });
        });
    }

    private String[] makeHeaders() {
        return new String[] {
                "User-Agent", this.config.userAgent(),
                "Accept", "application/json",
                "Accept-Encoding", "gzip",
        };
    }

    private interface PostStream {
        Collection<Post> posts();
        Instant latest();
    }

    private PostStreamBuilder bskyPostStreamBuilder(Function<String, HttpRequest> requestFunc, String name) {
        return new PostStreamBuilder() {
            @Override
            public String sourceName() {
                return name;
            }

            @Override
            public CompletableFuture<Optional<JsonElement>> getFeed(String cursor) {
                var request = requestFunc.apply(cursor);
                var bodyHandler = jsonBodyHandler(request.uri());
                return PostGetter.this.httpClient.sendAsync(request, bodyHandler)
                        .thenApply(HttpResponse::body);
            }

            @Override
            public String getCursor(JsonObject resObj, String oldCursor) {
                if (resObj.has("cursor")) {
                    JsonElement cursorElement = resObj.get("cursor");
                    if (cursorElement.isJsonPrimitive()) {
                        return cursorElement.getAsString();
                    }
                }

                return oldCursor;
            }

            @Override
            protected Optional<PostFeedResponse> getPosts(JsonObject response) {
                var feed = response.get("feed");
                if (!feed.isJsonArray()) {
                    LOGGER.warn("Got no feed array in response from {}, skipping: {}", this.sourceName(), response);
                    return Optional.empty();
                }

                JsonArray feedArray = feed.getAsJsonArray();

                return Optional.of(feedArray.asList().stream()
                                .filter(JsonObject.class::isInstance)
                                .map(JsonObject.class::cast)
                                .filter(j -> j.has("post") && j.get("post").isJsonObject())
                                .filter(j -> j.getAsJsonObject("post").getAsJsonObject("record").get("$type").getAsString().equals("app.bsky.feed.post"))
                                .map(PostGetter.this::parsePost))
                        .map(s -> new PostFeedResponse(feedArray.size(), s));
            }
        };
    }

    private abstract class PostStreamBuilder implements PostStream {
        private static final int PER_REQUEST_LIMIT = 100;
        private final List<Post> buffer = new ArrayList<>(PER_REQUEST_LIMIT);
        private Predicate<Post> predicate = $ -> true;
        private int limit = Integer.MAX_VALUE;
        private Instant mustBeNewerThan = Instant.MIN;
        private Instant latest = Instant.MIN;
        private Instant oldest = Instant.MAX;
        protected String cursor = null;

        private boolean built = false;
        private Supplier<CompletableFuture<Void>> task = null;
        private CompletableFuture<Void> fut = null;

        public abstract String sourceName();

        public abstract CompletableFuture<Optional<JsonElement>> getFeed(String cursor);

        protected abstract String getCursor(JsonObject resObj, String oldCursor);

        protected record PostFeedResponse(int count, Stream<Post> posts) {
        }

        protected abstract Optional<PostFeedResponse> getPosts(JsonObject response);

        public PostStreamBuilder limit(int limit) {
            if (this.built) {
                throw new IllegalStateException("Cannot modify an already-built PostStream!");
            }
            this.limit = limit;
            return this;
        }

        public PostStreamBuilder filter(Predicate<Post> predicate) {
            if (this.built) {
                throw new IllegalStateException("Cannot modify an already-built PostStream!");
            }
            this.predicate = this.predicate.and(predicate);
            return this;
        }

        public PostStreamBuilder newerThan(Instant instant) {
            if (this.built) {
                throw new IllegalStateException("Cannot modify an already-built PostStream!");
            }
            this.mustBeNewerThan = instant;
            return this;
        }

        public PostStream build() {
            this.built = true;
            this.task = () -> {
                var responseOpt = this.getFeed(this.cursor).join()
                        .filter(JsonElement::isJsonObject)
                        .map(JsonElement::getAsJsonObject);
                if (responseOpt.isPresent()) {
                    var resObj = responseOpt.get();

                    String oldCursor = this.cursor;
                    this.cursor = this.getCursor(resObj, this.cursor);

                    int bufferSize = this.buffer.size();
                    var feed = this.getPosts(resObj);
                    if (feed.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }

                    feed.get()
                            .posts()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(Post::createdAt))
                            .peek(p -> {
                                if (p.createdAt().isBefore(this.oldest)) {
                                    this.oldest = p.createdAt();
                                }
                            })
                            .filter(this.predicate)
                            .limit(this.limit - bufferSize)
                            .forEach(p -> {
                                if (p.createdAt().isAfter(this.latest)) {
                                    this.latest = p.createdAt();
                                }
                                if (p.createdAt().isBefore(this.oldest)) {
                                    this.oldest = p.createdAt();
                                }
                                this.buffer.add(p);
                            });
                    LOGGER.info("Got {} posts from {} (just chose {}/{})", this.buffer.size(), this.sourceName(), this.buffer.size() - bufferSize, feed.get().count());
                    if (this.oldest.isAfter(this.mustBeNewerThan) && !(this.buffer.size() >= this.limit)) {
                        if (this.cursor != null) {
                            if (Objects.equals(oldCursor, this.cursor)) {
                                LOGGER.info("Reached end of feed for {}", this.sourceName());
                                return CompletableFuture.completedFuture(null);
                            }
                            return this.runTask();
                        } else {
                            LOGGER.warn("Want to get more from {}, but we don't have a cursor", this.sourceName());
                            return CompletableFuture.completedFuture(null);
                        }
                    }

                    LOGGER.info("Got as much as we wanted from {}", this.sourceName());
                } else {
                    LOGGER.warn("Got no JSON Object in response from {}, skipping", this.sourceName());
                }
                return CompletableFuture.completedFuture(null);
            };

            this.fut = this.runTask();
            return this;
        }

        private CompletableFuture<Void> runTask() {
            return CompletableFuture.supplyAsync(this.task, PostGetter.this.executor)
                    .thenComposeAsync($ -> $, PostGetter.this.executor);
        }

        @Override
        public Collection<Post> posts() {
            if (!this.built) {
                throw new IllegalStateException("Cannot query a not-yet-built PostStream!");
            }
            this.fut.join();
            return this.buffer;
        }

        @Override
        public Instant latest() {
            if (!this.built) {
                throw new IllegalStateException("Cannot query a not-yet-built PostStream!");
            }
            this.fut.join();
            return this.latest;
        }
    }

    private Post parsePost(JsonObject j) {
        try {
            var post = j.getAsJsonObject("post");
            var author = post.getAsJsonObject("author");
            var record = post.getAsJsonObject("record");
            var createdAt = Instant.parse(record.get("createdAt").getAsString());
            var text = record.getAsJsonPrimitive("text").getAsString();
            var authorDid = author.getAsJsonPrimitive("did").getAsString();
            var uri = post.get("uri").getAsString();
            String reason;
            if (j.has("reason")) {
                reason = j.getAsJsonObject("reason").get("$type").getAsString();
            } else {
                reason = null;
            }
            Set<String> labels;
            // self labels
            if (record.get("labels") instanceof JsonObject labelsObj
                    && labelsObj.get("$type") instanceof JsonPrimitive labelType
                    && labelType.getAsString().equals("com.atproto.label.defs#selfLabels")) {
                labels = labelsObj.getAsJsonArray("values").asList().stream()
                        .map(o -> o.getAsJsonObject().getAsJsonPrimitive("val").getAsString())
                        .collect(Collectors.toSet());
            } else {
                labels = Set.of();
            }
            // moderation service labels
            if (post.get("labels") instanceof JsonArray labelsArr) {
                labels = new HashSet<>(labels);
                for (var label : labelsArr.getAsJsonArray()) {
                    labels.add(label.getAsJsonObject().get("val").getAsString());
                }
            }
            return new Post(new URI(uri),
                    authorDid,
                    text,
                    createdAt,
                    Optional.ofNullable(reason),
                    record.has("embed") && !(NOT_EMBEDS.contains(record.getAsJsonObject("embed").get("$type").getAsString())),
                    record.has("embed") && record.getAsJsonObject("embed").get("$type").getAsString().startsWith("app.bsky.embed.video"),
                    Set.copyOf(labels),
                    j);
        } catch (URISyntaxException | JsonParseException e) {
            LOGGER.error("Can't parse a post, ignoring it: {}", j, e);
            return null;
        }
    }
}
