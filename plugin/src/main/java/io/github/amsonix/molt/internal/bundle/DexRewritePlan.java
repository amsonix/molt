package io.github.amsonix.molt.internal.bundle;

import io.github.amsonix.molt.internal.rename.RenameMapping;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** DEX rewrite inputs that must be computed across all dex files. */
public final class DexRewritePlan {

    private final RenameMapping mapping;
    private final Set<String> publicClassDescriptors;

    DexRewritePlan(RenameMapping mapping, Set<String> publicClassDescriptors) {
        this.mapping = mapping;
        this.publicClassDescriptors = Collections.unmodifiableSet(
                new LinkedHashSet<>(publicClassDescriptors)
        );
    }

    public RenameMapping getMapping() {
        return mapping;
    }

    public Set<String> getPublicClassDescriptors() {
        return publicClassDescriptors;
    }
}
