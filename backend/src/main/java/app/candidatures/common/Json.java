package app.candidatures.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Lecture / écriture du JSON stocké en base (listes de chaînes, objets libres comme le contenu d'un CV). */
@Component
public class Json {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> out = mapper.readValue(json, MAP);
            return out == null ? new LinkedHashMap<>() : out;
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    public List<String> strings(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> out = mapper.readValue(json, STRINGS);
            return out == null ? new ArrayList<>() : new ArrayList<>(out);
        } catch (JacksonException e) {
            return new ArrayList<>();
        }
    }

    public Object read(String json) {
        return mapper.readValue(json, Object.class);
    }

    /** Copie profonde d'une structure JSON (cartes, listes, valeurs simples). */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, deepCopy(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(deepCopy(v)));
            return out;
        }
        return value;
    }
}
