package io.github.opencubicchunks.stirrin.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class MapUtil {
    public static <I, O, V> Map<O, V> mapKeys(Map<I, V> inMap, Function<I, O> mappingFunction) {
        Map<O, V> outMap = new HashMap<>(inMap.size());
        inMap.forEach((k, v) -> outMap.put(mappingFunction.apply(k), v));
        return outMap;
    }
}
