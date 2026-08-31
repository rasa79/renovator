package com.example.removal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EscapeSqlFormatterTest {

    @Test
    void escapesSingleQuotes() {
        // commons-lang 2.6 escapeSql doubles single quotes.
        assertEquals("O''Brien", EscapeSqlFormatter.escapeSql("O'Brien"));
    }
}
