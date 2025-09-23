import com.google.gson.JsonObject;
import com.williambl.buskymore.Post;
import com.williambl.buskymore.PostFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.*;

public class PostFilterTests {
    @BeforeAll
    public static void setup() {
        PostFilter.bootstrap();
    }

    private static boolean evalToBool(PostFilter.Fisp fisp, PostFilter.FilterContext ctx) {
        return PostFilter.Fisp.isTruthy(PostFilter.FUNCTIONS.eval(fisp, ctx));
    }

    private static PostFilter.FilterContext empty() {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), "auauau", "", Instant.MIN, Optional.empty(), false, false, Set.of(), new JsonObject()));
    }

    private static PostFilter.FilterContext postWithEmbed() {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), "auauau", "", Instant.MIN, Optional.empty(), true, false, Set.of(), new JsonObject()));
    }

    private static PostFilter.FilterContext postWithVideo() {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), "auauau", "", Instant.MIN, Optional.empty(), true, true, Set.of(), new JsonObject()));
    }

    private static PostFilter.FilterContext postWithReason(String reason) {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), "auauau", "", Instant.MIN, Optional.of(reason), false, false, Set.of(), new JsonObject()));
    }

    private static PostFilter.FilterContext postWithAuthor(String author) {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), author, "", Instant.MIN, Optional.empty(), false, false, Set.of(), new JsonObject()));
    }

    private static PostFilter.FilterContext selfAuthoredPost() {
        String authorDid = "me!!";
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), authorDid, "", Instant.MIN, Optional.empty(), false, false, Set.of(), new JsonObject()), authorDid);
    }

    private static PostFilter.FilterContext postWithLabels(String... labels) {
        return PostFilter.FilterContext.of(new Post(URI.create("https://example.com"), "auauau", "", Instant.MIN, Optional.empty(), false, false, Set.of(labels), new JsonObject()));
    }

    @Test
    public void allOf() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("all_of true true true"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("all_of true false true"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("all_of false false false"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("all_of"), empty()));
    }

    @Test
    public void anyOf() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("any_of true true true"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("any_of true false true"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("any_of false false false"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("any_of"), empty()));
    }

    @Test
    public void not() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("not false"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("not true"), empty()));
    }

    @Test
    public void hasEmbed() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("has_embed"), postWithEmbed()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("has_embed"), empty()));
    }

    @Test
    public void hasVideo() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("has_video"), postWithVideo()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("has_video"), empty()));
    }

    @Test
    public void reasonIs() {
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("reason_is bllahhhh"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("reason_is beep boop bar blah"), postWithReason("blah")));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("reason_is beep"), postWithReason("beep")));
    }

    @Test
    public void isRetweet() {
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("is_retweet"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("is_retweet"), postWithReason("app.bsky.feed.defs#reasonRepost")));
    }

    @Test
    public void authorIs() {
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("author_is me"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("author_is you"), postWithAuthor("you")));
    }

    @Test
    public void isAuthoredBySelf() {
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("is_authored_by_self"), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("is_authored_by_self"), postWithAuthor("me!!")));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("is_authored_by_self"), selfAuthoredPost()));
    }

    @Test
    public void isSelfRetweet() {
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("is_self_retweet"), empty()));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("is_self_retweet"), postWithAuthor("me!!")));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("is_self_retweet"), selfAuthoredPost()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("is_self_retweet"), postWithReason("app.bsky.feed.defs#reasonRepost")));
    }

    @Test
    public void labelsContains() {
        final int labelCount = 5;
        String[] labels = new String[labelCount];
        for (int i = 0; i < 5; i++) {
            labels[i] = UUID.randomUUID().toString();
        }
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("labels_contains "+labels[0]), postWithLabels(labels[0])));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("labels_contains "+labels[0]), empty()));
        Assertions.assertFalse(evalToBool(PostFilter.Fisp.parse("labels_contains"), postWithLabels(labels[0])));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("labels_contains "+String.join(" ", labels)), postWithLabels(labels[new Random().nextInt(labels.length)])));
        Assertions.assertTrue(evalToBool(PostFilter.Fisp.parse("labels_contains "+labels[new Random().nextInt(labels.length)]), postWithLabels(labels)));
    }
}
