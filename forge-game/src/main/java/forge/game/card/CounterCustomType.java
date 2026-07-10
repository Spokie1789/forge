package forge.game.card;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

import java.util.concurrent.ConcurrentHashMap;

public record CounterCustomType(String keyword) implements CounterType {
    // Concurrent: lazily populated from game threads; a plain HashMap corrupts
    // under concurrent put (concurrent games in one JVM).
    private static final Map<String, CounterCustomType> sMap = new ConcurrentHashMap<>();

    public static CounterCustomType get(String s) {
        return sMap.computeIfAbsent(s, CounterCustomType::new);
    }

    public static Set<CounterType> getValues() {
        return new LinkedHashSet<CounterType>(sMap.values());
    }
    
    @Override
    public String toString() {
        return keyword;
    }

    public String getName() {
        return keyword;
    }
}
