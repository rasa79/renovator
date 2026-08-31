package com.example.removal;

import org.apache.commons.lang.StringEscapeUtils;

/**
 * Uses exactly one commons-lang 2.6 API that has NO equivalent in commons-lang3:
 * `StringEscapeUtils.escapeSql` (SQL-escaping was removed from the lang3 family
 * entirely). The upgrade therefore fails to compile with a "cannot find symbol"
 * error naming `escapeSql` — the signal the planner consumes (see README.md).
 */
public final class EscapeSqlFormatter {

    private EscapeSqlFormatter() {
    }

    public static String escapeSql(String input) {
        return StringEscapeUtils.escapeSql(input);
    }
}
