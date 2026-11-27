package io.github.jaeseongs95.opermand.migrate.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.json.JsonParser;

/**
 * A JSON object of a 2.x body, read through the values Boot's {@link JsonParser} gives (maps, lists, strings, numbers, booleans). A key that is absent or null answers null;
 * a key whose value has another type than asked for rejects the row, because a body that does not have the 2.x shape is not guessed at.
 */
final class JsonObject {

    private final Map<String, Object> values;

    private JsonObject(Map<String, Object> values) {
        this.values = values;
    }

    static JsonObject parse(JsonParser parser, String text) {
        try {
            return new JsonObject(parser.parseMap(text));
        } catch (RuntimeException e) {
            throw new RowRejectedException("the body is not a JSON object: " + e.getMessage());
        }
    }

    boolean has(String key) {
        return values.get(key) != null;
    }

    /** The nested object, or null. */
    JsonObject object(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new RowRejectedException("\"" + key + "\" has a key that is not text");
                }
                copy.put(name, entry.getValue());
            }
            return new JsonObject(copy);
        }
        throw new RowRejectedException("\"" + key + "\" is not an object");
    }

    /** The first of the objects that is not null; null when all are. */
    static JsonObject firstOf(JsonObject... candidates) {
        for (JsonObject candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /** The text, or null. */
    String string(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new RowRejectedException("\"" + key + "\" is not text");
    }

    /** The whole number, or null. A number with a fraction is refused. */
    Long integer(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 9.0e15) {
            return number.longValue();
        }
        throw new RowRejectedException("\"" + key + "\" is not a whole number");
    }

    /** The flag, or null. */
    Boolean bool(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new RowRejectedException("\"" + key + "\" is not true or false");
    }

    /** The list of texts, or null. */
    List<String> strings(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> texts = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String text)) {
                    throw new RowRejectedException("\"" + key + "\" holds an item that is not text");
                }
                texts.add(text);
            }
            return texts;
        }
        throw new RowRejectedException("\"" + key + "\" is not a list");
    }
}
