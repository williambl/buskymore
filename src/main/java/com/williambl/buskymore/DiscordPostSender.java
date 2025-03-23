package com.williambl.buskymore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DiscordPostSender {
    private final JDA jda;
    private final Map<BskyPostGetter, List<String>> postGetters = new HashMap<>();

    public record Config(String token, List<Mapping> mappings) {
        public record Mapping(BskyPostGetter.Config getterConfig, List<String> channelIds) {}
    }

    public DiscordPostSender(Config config, ExecutorService executor) {
        this.jda = JDABuilder.createLight(config.token())
                .build();
        for (var mapping : config.mappings) {
            this.postGetters.put(new BskyPostGetter(mapping.getterConfig(), executor), mapping.channelIds());
        }
    }

    public CompletableFuture<Void> run() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : this.postGetters.entrySet()) {
            var postGetter = entry.getKey();
            var channelIds = entry.getValue();
            try {
                var state = postGetter.readState();
                futures.add(postGetter.run(state).thenCompose(res ->
                        this.sendPosts(res.posts(), channelIds).thenAccept($ -> {
                            try {
                                postGetter.writeState(res.state());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public void stop() {
        this.jda.shutdown();
    }

    private CompletableFuture<Void> sendPosts(List<Post> posts, List<String> channelIds) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (var channelId : channelIds) {
            var channel = this.jda.getChannelById(TextChannel.class, channelId);

            for (var post : posts) {
                if (channel != null) {
                    futures.add(channel.sendMessage("@everyone " + makeEmbedUrl(post.uri())).submit());
                }
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static String makeEmbedUrl(URI uri) {
        String did = uri.getAuthority();
        String postId = uri.getPath().split("/")[2];
        return "https://fxbsky.app/profile/%s/post/%s".formatted(did, postId);
    }
}
