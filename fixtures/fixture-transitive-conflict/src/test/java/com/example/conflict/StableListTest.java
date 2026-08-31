package com.example.conflict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StableListTest {

    @Test
    void copiesInOrder() {
        assertEquals(List.of("a", "b"), StableList.copyOf(List.of("a", "b")));
    }
}
