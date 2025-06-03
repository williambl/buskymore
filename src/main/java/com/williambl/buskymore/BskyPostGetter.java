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
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BskyPostGetter {
    public static final Logger LOGGER = LoggerFactory.getLogger(BskyPostGetter.class);
    private static final Set<String> NOT_EMBEDS = Set.of("app.bsky.embed.external", "app.bsky.embed.record");

    public record Config(String userAgent, int backlogDays, int maxBacklogPosts, String statePath, List<PostSource> postSources) {

        sealed public interface PostSource {
            record User(String userDid, PostFilter.Fisp filter) implements PostSource {
                @Override
                public String uniqueKey() {
                    return this.userDid;
                }
            }
            record Feed(String userDid, String feedKey, PostFilter.Fisp filter) implements PostSource {
                @Override
                public String uniqueKey() {
                    return "%s/app.bsky.feed.generator/%s".formatted(this.userDid, this.feedKey);
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

    public BskyPostGetter(Config config, ExecutorService executor) {
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
            List<CompletableFuture<List<Post>>> postFutures = new ArrayList<>();
            List<Config.PostSource> postSources = this.config.postSources;
            Set<Config.PostSource> newSources = new HashSet<>();
            for (var postSource : postSources) {
                if (!state.latestPostTimestamps.containsKey(postSource.uniqueKey())) {
                    newSources.add(postSource);
                }
                Instant latestPostTimestamp = state.latestPostTimestamps().getOrDefault(postSource.uniqueKey(), Instant.now().minus(this.config.backlogDays(), ChronoUnit.DAYS));
                postFutures.add(switch (postSource) {
                    case Config.PostSource.Feed feed -> this.getPosts(feed, latestPostTimestamp);
                    case Config.PostSource.User user -> this.getPosts(user, latestPostTimestamp);
                });
            }
            Map<String, Instant> latestPostTimestamps = new HashMap<>(state.latestPostTimestamps());
            List<Post> posts = new ArrayList<>();
            for (int i = 0; i < postFutures.size(); i++) {
                var source = postSources.get(i);
                var future = postFutures.get(i);
                Instant latest = Instant.MIN;
                int backlogPostCount = 0;
                for (var post : future.join()) {
                    if (post.createdAt().isAfter(latest)) {
                        latest = post.createdAt();
                    }
                    posts.add(post);
                    if (newSources.contains(source) && backlogPostCount++ > this.config.maxBacklogPosts()) {
                        break;
                    }
                }
                Instant finalLatest = latest;
                latestPostTimestamps.compute(source.uniqueKey(), (k, oldLatest) -> oldLatest == null || oldLatest.isBefore(finalLatest) ? finalLatest : oldLatest);
            }
            posts.sort(Comparator.comparing(Post::createdAt));
            return new Result(new State(latestPostTimestamps), posts);
        }, this.executor);
    }

    private CompletableFuture<List<Post>> getPosts(Config.PostSource.Feed feed, Instant latest) {
        var filter = PostFilter.FUNCTIONS.build(feed.filter(), Map.of("userDid", feed.userDid()));
        String atUri = "at://%s/app.bsky.feed.generator/%s".formatted(feed.userDid(), feed.feedKey());
        String queryString = "?feed=%s".formatted(URLEncoder.encode(atUri, StandardCharsets.UTF_8));
        URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed"+queryString);
        var request = HttpRequest.newBuilder(uri)
                .headers(this.makeHeaders())
                .GET()
                .build();
        var bodyHandler = this.jsonBodyHandler(uri);
        return this.httpClient.sendAsync(request, bodyHandler)
                .thenApply(HttpResponse::body)
                .thenApply(j -> this.parseFeed(j, p -> p.createdAt().isAfter(latest) && filter.test(p)));
    }

    private CompletableFuture<List<Post>> getPosts(Config.PostSource.User user, Instant latest) {
        var filter = PostFilter.FUNCTIONS.build(user.filter(), Map.of("userDid", user.userDid()));
        String queryString = "?actor=%s".formatted(URLEncoder.encode(user.userDid(), StandardCharsets.UTF_8));
        URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed"+queryString);
        var request = HttpRequest.newBuilder(uri)
                .headers(this.makeHeaders())
                .GET()
                .build();
        var bodyHandler = this.jsonBodyHandler(uri);
        return this.httpClient.sendAsync(request, bodyHandler)
                .thenApply(HttpResponse::body)
                .thenApply(j -> this.parseFeed(j, p -> p.createdAt().isAfter(latest) && filter.test(p)));
    }

    private HttpResponse.BodyHandler<Optional<JsonElement>> jsonBodyHandler(URI uri) {
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

    private List<Post> parseFeed(Optional<JsonElement> jsonElement, Predicate<Post> predicate) {
        if (jsonElement.filter(j -> j.isJsonObject() && j.getAsJsonObject().has("feed")).isEmpty()) {
            return List.of();
        }

        var feed = jsonElement.get().getAsJsonObject().get("feed");
        if (!feed.isJsonArray()) {
            return List.of();
        }

        return feed.getAsJsonArray().asList().stream()
                .filter(JsonObject.class::isInstance)
                .map(JsonObject.class::cast)
                .filter(j -> j.has("post") && j.get("post").isJsonObject())
                .filter(j -> j.getAsJsonObject("post").getAsJsonObject("record").get("$type").getAsString().equals("app.bsky.feed.post"))
                .map(j -> {
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
                                Set.copyOf(labels));
                    } catch (URISyntaxException | JsonParseException e) {
                        LOGGER.error("Can't parse a post, ignoring it: {}", j, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(predicate)
                .sorted(Comparator.comparing(Post::createdAt))
                .toList();
    }
}
