package com.williambl.buskymore;

import com.google.gson.JsonObject;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public record Post(URI uri, String authorDid, String text, Instant createdAt, Optional<String> reason, boolean hasEmbeds, Set<String> labels, JsonObject json) {
    @Override
    public String toString() {
        return "%s [created %s]%s @%s embed:%s text:%s labels:%s".formatted(this.uri, this.createdAt, this.reason.map(rs -> " reason: " + rs).orElse(""), this.authorDid, this.hasEmbeds, this.text, String.join(", ", this.labels));
    }
}
