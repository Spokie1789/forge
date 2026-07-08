package forge.game.card;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import java.util.concurrent.ConcurrentHashMap;

import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordView;

public record CounterKeywordType(KeywordView keyword) implements CounterType {

    // Rule 122.1b
    static ImmutableList<String> keywordCounter = ImmutableList.of(
            "Flying", "First Strike", "Double Strike", "Deathtouch", "Decayed", "Exalted", "Haste", "Hexproof",
            "Indestructible", "Lifelink", "Menace", "Reach", "Shadow", "Trample", "Vigilance");
    // Concurrent: lazily populated from game threads; a plain HashMap corrupts
    // under concurrent put (concurrent games in one JVM).
    private static final Map<String, CounterKeywordType> sMap = new ConcurrentHashMap<>();

    public static CounterKeywordType get(String s) {
        return sMap.computeIfAbsent(s, k -> new CounterKeywordType(Keyword.getInstance(k).getView()));
    }

    public static Set<CounterType> getValues() {
        // add fixed first
        Set<CounterType> result = keywordCounter.stream().map(CounterKeywordType::get).collect(Collectors.toCollection(LinkedHashSet::new));
        // add variable ones later
        result.addAll(sMap.values());
        return result;
    }
    
    @Override
    public String toString() {
        return keyword.original();
    }

    public String getName() {
        return keyword.title();
    }

    @Override
    public String getCounterOnCardDisplayName() {
        return keyword.title();
    }

    @Override
    public boolean isKeywordCounter() {
        return true;
    }

    public static boolean isKeywordCounter(String keyword) {
        if (keyword.startsWith("Hexproof:")) {
            return true;
        }
        if (keyword.startsWith("Trample:")) {
            return true;
        }
        return keywordCounter.contains(keyword);
    }

    @Override
    public CounterAiCategory getAiCategory() {
        if (Keyword.DECAYED.equals(keyword.keyword())) {
            return CounterAiCategory.Negative;
        }
        return CounterAiCategory.Positive;
    }
}
