package com.github.orenaidaucac.royPodium.podium;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PodiumDefinitionTest {

    @Test
    void matchesSingleAndRangeRankExpressions() {
        assertTrue(PodiumDefinition.matchesRankExpression("1", 1));
        assertFalse(PodiumDefinition.matchesRankExpression("1", 2));
        assertTrue(PodiumDefinition.matchesRankExpression("4-10", 4));
        assertTrue(PodiumDefinition.matchesRankExpression("4-10", 7));
        assertTrue(PodiumDefinition.matchesRankExpression("4-10", 10));
        assertFalse(PodiumDefinition.matchesRankExpression("4-10", 11));
        assertFalse(PodiumDefinition.matchesRankExpression("invalid", 1));
    }

    @Test
    void resolvesCommandsForMatchingRank() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("1", List.of("eco give {player} 5000"));
        rules.put("2-3", List.of("eco give {player} 2000"));

        PodiumDefinition def = PodiumDefinition.builder()
                .id("kills")
                .rankRules(rules)
                .build();

        assertEquals(List.of("eco give {player} 5000"), def.getCommandsForRank(1));
        assertEquals(List.of("eco give {player} 2000"), def.getCommandsForRank(2));
        assertEquals(List.of("eco give {player} 2000"), def.getCommandsForRank(3));
        assertTrue(def.getCommandsForRank(4).isEmpty());
    }
}
