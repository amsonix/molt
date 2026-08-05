package io.github.amsonix.molt.internal.bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/** post-R8 synthetic 伴生类识别用的工程包前缀（ThreadLocal，支持 Gradle 并行 variant）。 */
final class DexProjectClassScope {

    private static final List<String> DEFAULT_PREFIXES = List.of();

    private static final ThreadLocal<List<String>> PREFIXES =
            ThreadLocal.withInitial(() -> DEFAULT_PREFIXES);

    private DexProjectClassScope() {
    }

    static boolean isProjectClass(String dotName) {
        for (String prefix : PREFIXES.get()) {
            if (dotName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static <T> T callWithPrefixes(List<String> prefixes, Callable<T> action) throws Exception {
        List<String> previous = PREFIXES.get();
        PREFIXES.set(normalize(prefixes));
        try {
            return action.call();
        } finally {
            PREFIXES.set(previous);
        }
    }

    private static List<String> normalize(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return DEFAULT_PREFIXES;
        }
        List<String> normalized = new ArrayList<>(prefixes.size());
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            normalized.add(prefix.endsWith(".") ? prefix : prefix + ".");
        }
        return normalized.isEmpty() ? DEFAULT_PREFIXES : Collections.unmodifiableList(normalized);
    }
}
