package com.williambl.buskymore;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.*;

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
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class BskyPostGetter {

    private static final Set<String> NOT_EMBEDS = Set.of("app.bsky.embed.external", "app.bsky.embed.record");

    public record Config(String userAgent, Path state, List<PostSource> postSources) {

        sealed interface PostSource {
            record User(String userDid, boolean includeRetweets, boolean includeNoEmbed) implements PostSource {
                @Override
                public String uniqueKey() {
                    return this.userDid;
                }
            }
            record Feed(String userDid, String feedKey) implements PostSource {
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

    public BskyPostGetter(Config config) {
        this.config = config;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .executor(this.executor)
                .build();
    }

    public State readState() throws IOException {
        if (!Files.exists(this.config.state)) {
            return new State(Map.of());
        }

        return new State(Arrays.stream(Files.readString(this.config.state).split("\n"))
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
        try (var writer = Files.newBufferedWriter(this.config.state(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
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
            for (var postSource : postSources) {
                Instant latestPostTimestamp = state.latestPostTimestamps().getOrDefault(postSource.uniqueKey(), Instant.now().minus(1, ChronoUnit.HALF_DAYS));
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
                for (var post : future.join()) {
                    if (post.createdAt().isAfter(latest)) {
                        latest = post.createdAt();
                    }
                    posts.add(post);
                }
                Instant finalLatest = latest;
                latestPostTimestamps.compute(source.uniqueKey(), (k, oldLatest) -> oldLatest == null || oldLatest.isBefore(finalLatest) ? finalLatest : oldLatest);
            }
            posts.sort(Comparator.comparing(Post::createdAt));
            return new Result(new State(latestPostTimestamps), posts);
        }, this.executor);
    }

    private CompletableFuture<List<Post>> getPosts(Config.PostSource.Feed feed, Instant latest) {
        String atUri = "at://%s/app.bsky.feed.generator/%s".formatted(feed.userDid(), feed.feedKey());
        String queryString = "?feed=%s".formatted(URLEncoder.encode(atUri, StandardCharsets.UTF_8));
        URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed"+queryString);
        var request = HttpRequest.newBuilder(uri)
                .headers(this.makeHeaders())
                .GET()
                .build();
        var bodyHandler = this.jsonBodyHandler();
        return this.httpClient.sendAsync(request, bodyHandler)
                .thenApply(HttpResponse::body)
                .thenApply(j -> this.parseFeed(j, p -> p.createdAt().isAfter(latest)));
    }

    private CompletableFuture<List<Post>> getPosts(Config.PostSource.User user, Instant latest) {
        String queryString = "?actor=%s".formatted(URLEncoder.encode(user.userDid(), StandardCharsets.UTF_8));
        URI uri = URI.create("https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed"+queryString);
        var request = HttpRequest.newBuilder(uri)
                .headers(this.makeHeaders())
                .GET()
                .build();
        var bodyHandler = this.jsonBodyHandler();
        return this.httpClient.sendAsync(request, bodyHandler)
                .thenApply(HttpResponse::body)
                .thenApply(j -> this.parseFeed(j, p -> p.createdAt().isAfter(latest)
                        && (user.includeRetweets() || p.reason().filter("app.bsky.feed.defs#reasonRepost"::equals).isEmpty())
                && (user.includeNoEmbed() || p.hasEmbeds())));
    }

    private HttpResponse.BodyHandler<Optional<JsonElement>> jsonBodyHandler() {
        return MoreBodyHandlers.decoding(responseInfo -> {
            HttpResponse.BodySubscriber<String> string = HttpResponse.BodyHandlers.ofString().apply(responseInfo);
            if (responseInfo.statusCode() / 100 != 2) {
                return HttpResponse.BodySubscribers.mapping(
                        string,
                        str -> {
                            //this.logger.debug("From: {}, received non-OK status code: {}\nWith body: {}", uri, responseInfo.statusCode(), str);
                            return Optional.empty();//DataResult.error(() -> "Received non-OK status code %s (with body %s)".formatted(responseInfo.statusCode(), str));
                        });
            }

            System.out.println(string);

            return HttpResponse.BodySubscribers.mapping(
                    string,
                    s -> {
                        try {
                            System.out.printf("received %s%n", s);
                            return Optional.of(JsonParser.parseString(s));
                        } catch (JsonParseException e) {
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
                        var record = post.getAsJsonObject("record");
                        var createdAt = Instant.parse(record.get("createdAt").getAsString());
                        var uri = post.get("uri").getAsString();
                        String reason;
                        if (j.has("reason")) {
                            reason = j.getAsJsonObject("reason").get("$type").getAsString();
                        } else {
                            reason = null;
                        }
                        return new Post(new URI(uri), createdAt, Optional.ofNullable(reason), record.has("embed") && !(NOT_EMBEDS.contains(record.getAsJsonObject("embed").get("$type").getAsString())));
                    } catch (URISyntaxException | JsonParseException e) {
                        // ignore
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .filter(predicate)
                .toList();
    }
}
