package com.williambl.buskymore;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws IOException {
        Path configPath;
        if (args.length == 0) {
            configPath = Path.of("./buskymore.json");
        } else {
            configPath = Path.of(args[0]);
        }
        var configString = Files.readString(configPath);
        var config = new Gson().fromJson(configString, DiscordPostSender.Config.class);
        var sender = new DiscordPostSender(config, Executors.newVirtualThreadPerTaskExecutor());
        sender.run()
                .thenRun(sender::stop)
                .join();
    }
}
