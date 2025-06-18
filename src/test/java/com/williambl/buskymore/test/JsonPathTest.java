package com.williambl.buskymore.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.williambl.buskymore.JsonPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.williambl.buskymore.JsonPath.*;


// tests from https://goessner.net/articles/JsonPath/index.html#e3
public class JsonPathTest {
    private static final JsonElement STORE_JSON = JsonParser.parseString("""
            { "store": {
                "book": [
                  { "category": "reference",
                    "author": "Nigel Rees",
                    "title": "Sayings of the Century",
                    "price": 8.95
                  },
                  { "category": "fiction",
                    "author": "Evelyn Waugh",
                    "title": "Sword of Honour",
                    "price": 12.99
                  },
                  { "category": "fiction",
                    "author": "Herman Melville",
                    "title": "Moby Dick",
                    "isbn": "0-553-21311-3",
                    "price": 8.99
                  },
                  { "category": "fiction",
                    "author": "J. R. R. Tolkien",
                    "title": "The Lord of the Rings",
                    "isbn": "0-395-19395-8",
                    "price": 22.99
                  }
                ],
                "bicycle": {
                  "color": "red",
                  "price": 19.95
                }
              }
            }
            """);

    @Test
    public void authorsOfAllBooks() {
        JsonPath path = of(dot("store"), dot("book"), dot(all()), dot("author"));
        List<String> expected = List.of("Nigel Rees", "Evelyn Waugh", "Herman Melville", "J. R. R. Tolkien");
        Assertions.assertEquals(expected, path.select(STORE_JSON).map(JsonElement::getAsString).toList());
    }

    @Test
    public void allAuthors() {
        JsonPath path = of(dotdot(name("author")));
        List<String> expected = List.of("Nigel Rees", "Evelyn Waugh", "Herman Melville", "J. R. R. Tolkien");
        Assertions.assertEquals(expected, path.select(STORE_JSON).map(JsonElement::getAsString).toList());
    }

    @Test
    public void allThingsInStore() {
        JsonPath path = of(dot("store"), dot(all()));
        List<JsonElement> expected = List.copyOf(STORE_JSON.getAsJsonObject().get("store").getAsJsonObject().asMap().values());
        Assertions.assertEquals(expected, path.select(STORE_JSON).toList());
    }

    @Test
    public void priceOfEverythingInStore() {
        JsonPath path = of(dot("store"), dotdot(name("price")));
        List<Double> expected = List.of(8.95, 12.99, 8.99, 22.99, 19.95);
        Assertions.assertEquals(expected, path.select(STORE_JSON).map(JsonElement::getAsDouble).toList());
    }

    @Test
    public void thirdBook() {
        JsonPath path = of(dotdot(name("book")), dot(2));
        List<JsonElement> expected = List.of(STORE_JSON.getAsJsonObject().getAsJsonObject("store").getAsJsonArray("book").get(2));
        Assertions.assertEquals(expected, path.select(STORE_JSON).toList());
    }

    @Test
    public void firstTwoBooks() {
        JsonArray booksArray = STORE_JSON.getAsJsonObject().getAsJsonObject("store").getAsJsonArray("book");
        List<JsonElement> expected = List.of(booksArray.get(0), booksArray.get(1));
        {
            JsonPath path = of(dotdot(name("book")), dot(idx(0), idx(1)));
            Assertions.assertEquals(expected, path.select(STORE_JSON).toList());
        }
        {
            JsonPath path = of(dotdot(name("book")), dot(slice(null, 2)));
            Assertions.assertEquals(expected, path.select(STORE_JSON).toList());
        }
    }
}
