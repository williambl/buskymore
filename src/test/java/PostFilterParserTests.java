import com.williambl.buskymore.PostFilter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

public class PostFilterParserTests {
    @Test
    public void parsesWithQuotes() {
        var result = PostFilter.Fisp.parse(" not ( contains_regex \"(ych)|((sticker)|(icon)|(comm)|(sketch)[\\w\\s_-]+for)\" )");
        Assertions.assertEquals(2, result.values().size());
        Assertions.assertInstanceOf(PostFilter.Fisp.Array.class, result.argument());
        Assertions.assertEquals(2, result.argument().cast(PostFilter.Fisp.Array.class).values().size());
        Assertions.assertInstanceOf(PostFilter.Fisp.Str.class, result.argument().cast(PostFilter.Fisp.Array.class).argument());
        Assertions.assertEquals("(ych)|((sticker)|(icon)|(comm)|(sketch)[\\w\\s_-]+for)", result.argument().cast(PostFilter.Fisp.Array.class).argument().cast(PostFilter.Fisp.Str.class).value());
    }
}
