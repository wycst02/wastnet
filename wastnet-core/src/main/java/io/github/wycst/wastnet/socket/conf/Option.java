package io.github.wycst.wastnet.socket.conf;

import java.util.function.Function;

/**
 * Strongly-typed, globally-unique configuration option keyed by a fixed {@link #index}.
 * Declared as {@code public static final} constants in registry classes (e.g. {@code SocketOptions},
 * {@code HttpOptions}) and used directly as {@code Map} keys in {@code NioConfig}; absent overrides
 * fall back to {@link #value}.
 *
 * @param <T> the option value type
 * @since 1.0.2
 */
public final class Option<T> {

    public final int index;
    public final T value;
    public final Class<T> type;

    /**
     * Normalizer applied to every override written via {@code NioConfig#option(Option, value)}.
     * Never {@code null} — options without a range constraint use the identity function. The default
     * value ({@link #value}) is assumed already valid.
     */
    public final Function<T, T> normalizer;

    private Option(int index, T value, Class<T> type, Function<T, T> normalizer) {
        this.index = index;
        this.value = value;
        this.type = type;
        this.normalizer = normalizer;
    }

    /**
     * Create a new option without a range constraint (identity normalizer).
     *
     * @param index        fixed ordinal assigned at declaration time (see {@link #index})
     * @param defaultValue value returned when no instance overrides it
     * @param type         runtime class of the value, used for safe casting
     * @param <T>          value type
     * @return the option instance
     */
    static <T> Option<T> of(int index, T defaultValue, Class<T> type) {
        return new Option<>(index, defaultValue, type, Function.identity());
    }

    /**
     * Create a new option with a range constraint.
     *
     * @param index        fixed ordinal assigned at declaration time (see {@link #index})
     * @param defaultValue value returned when no instance overrides it (assumed already valid)
     * @param type         runtime class of the value, used for safe casting
     * @param normalizer   normalizer applied to every override (may return the same or a normalized
     *                     value); must be non-null — pass {@code Function.identity()} for no constraint
     * @param <T>          value type
     * @return the option instance
     */
    static <T> Option<T> of(int index, T defaultValue, Class<T> type, Function<T, T> normalizer) {
        return new Option<>(index, defaultValue, type, normalizer);
    }

    // index is globally unique across all option registries, so identity equality by ordinal is safe.
    @Override
    public int hashCode() {
        return index;
    }
}
