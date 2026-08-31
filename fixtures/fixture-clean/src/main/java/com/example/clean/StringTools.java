package com.example.clean;

import org.apache.commons.lang3.StringUtils;

/**
 * Lives on exactly one commons-lang3 API that is identical between 3.12.0 and
 * 3.14.0, so a version bump alone keeps the fixture green (the happy path).
 */
public final class StringTools {

    private StringTools() {
    }

    public static String reverse(String input) {
        return StringUtils.reverse(input);
    }
}
