package io.github.wycst.wastnet.http.annotation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

/**
 * Shared registry of parameter value converters, reused by {@code @RequestParam}/{@code @PathParam}
 * and {@code @Value} binding. Built-in primitives/String are locked; custom types via
 * AnnotationRouterHandler.registerParamConverter (generic, type-checked).
 */
final class ParamValueConverters {

    private static final Map<Class<?>, Function<String, Object>> CONVERTERS = new HashMap<>();
    private static final Set<Class<?>> LOCKED;

    static {
        CONVERTERS.put(String.class, s -> s);
        CONVERTERS.put(boolean.class, Boolean::parseBoolean);
        CONVERTERS.put(Boolean.class, Boolean::parseBoolean);
        CONVERTERS.put(char.class, s -> s.isEmpty() ? '\0' : s.charAt(0));
        CONVERTERS.put(Character.class, s -> s.isEmpty() ? '\0' : s.charAt(0));
        CONVERTERS.put(byte.class, Byte::parseByte);
        CONVERTERS.put(Byte.class, Byte::parseByte);
        CONVERTERS.put(short.class, Short::parseShort);
        CONVERTERS.put(Short.class, Short::parseShort);
        CONVERTERS.put(int.class, Integer::parseInt);
        CONVERTERS.put(Integer.class, Integer::parseInt);
        CONVERTERS.put(long.class, Long::parseLong);
        CONVERTERS.put(Long.class, Long::parseLong);
        CONVERTERS.put(float.class, Float::parseFloat);
        CONVERTERS.put(Float.class, Float::parseFloat);
        CONVERTERS.put(double.class, Double::parseDouble);
        CONVERTERS.put(Double.class, Double::parseDouble);

        // Frozen snapshot of built-in converters (primitives / String) that applications cannot override.
        LOCKED = Collections.unmodifiableSet(new HashSet<>(CONVERTERS.keySet()));

        // Common non-primitive converters shipped as overridable built-ins.
        CONVERTERS.put(BigDecimal.class, BigDecimal::new);
        CONVERTERS.put(BigInteger.class, BigInteger::new);
        CONVERTERS.put(LocalDate.class, LocalDate::parse);
        CONVERTERS.put(LocalDateTime.class, LocalDateTime::parse);
    }

    private ParamValueConverters() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Function<String, Object> resolve(Class<?> type) {
        Function<String, Object> f = CONVERTERS.get(type);
        if (f != null) return f;
        if (type.isEnum()) {
            Class<Enum> enumType = (Class<Enum>) type;
            return s -> Enum.valueOf(enumType, s);
        }
        return s -> null;
    }

    static void ensureConvertible(Class<?> type) {
        if (CONVERTERS.containsKey(type) || type.isEnum()) return;
        throw new IllegalStateException("No type converter for " + type.getName()
                + "; register one via AnnotationRouterHandler.registerParamConverter(...)");
    }

    static void register(Class<?> type, Function<String, Object> converter) {
        if (LOCKED.contains(type)) {
            throw new IllegalArgumentException("Cannot override locked built-in converter for " + type.getName());
        }
        CONVERTERS.put(type, converter);
    }
}
