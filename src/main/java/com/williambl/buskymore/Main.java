package com.williambl.buskymore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws InterruptedException, IOException {
        BskyPostGetter postGetter = new BskyPostGetter(new BskyPostGetter.Config("buskymore", Path.of("./bsky_post_getter_state"), List.of(
        )));
        var state = postGetter.readState();
        var postGetting = postGetter.run(state);

        JDA jda = JDABuilder.createLight("")
                .build()
                .awaitReady();
        var channel = jda.getChannelById(TextChannel.class, "");

        var result = postGetting.join();
        for (var post : result.posts()) {
            if (channel != null) {
                channel.sendMessage("@everyone "+makeEmbedUrl(post.uri())).queue();
            }
        }

        postGetter.writeState(result.state());

        jda.shutdown();
    }

    private static String makeEmbedUrl(URI uri) {
        String did = uri.getAuthority();
        String postId = uri.getPath().split("/")[2];
        return "https://fxbsky.app/profile/%s/post/%s".formatted(did, postId);
    }
}
