package app.candidatures.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lecture tolérante des valeurs reçues en JSON (le navigateur peut envoyer n'importe quoi). */
public final class Values {

    private Values() {}

    /** Chaîne de la valeur, vide si absente. */
    public static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    public static String trimmed(Object value) {
        return str(value).strip();
    }

    public static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0;
            case String s -> !s.isEmpty();
            case List<?> l -> !l.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : null;
    }

    /** Liste de chaînes non vides, sans doublons à la casse près, dans l'ordre d'arrivée. */
    public static List<String> cleanList(Object value) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                String s = trimmed(o);
                if (!s.isEmpty() && seen.add(s.toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** Chaînes non vides d'une liste (doublons conservés). */
    public static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            for (Object o : l) {
                String s = trimmed(o);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public static Long asLong(Object value) {
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.longValue();
        }
        return null;
    }
}
