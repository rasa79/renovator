package com.example.clean;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StringToolsTest {

    @Test
    void reversesStrings() {
        assertEquals("cba", StringTools.reverse("abc"));
        assertEquals("", StringTools.reverse(""));
    }
}
