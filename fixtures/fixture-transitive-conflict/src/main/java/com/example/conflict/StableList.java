package com.example.conflict;

import com.google.common.collect.ImmutableList;

/**
 * Uses only the stable guava surface (ImmutableList.copyOf) so the ONLY breakage
 * in this fixture is the enforcer's dependencyConvergence rule: guice 7.0.0
 * transitively pins guava 31.0.1-jre, so a direct bump to 33.4.8-jre without
 * handling the transitive pin fails `validate` naming guava (see README.md).
 */
public final class StableList {

    private StableList() {
    }

    public static ImmutableList<String> copyOf(java.util.List<String> input) {
        return ImmutableList.copyOf(input);
    }
}
