package com.miniongvang.config;

import com.miniongvang.integration.FakeRouteProvider;
import com.miniongvang.integration.GoongRouteProvider;
import com.miniongvang.integration.RouteProvider;
import com.miniongvang.service.RouteService;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

public final class RouteConfiguration {
    public static final String ATTRIBUTE = RouteService.class.getName();

    private RouteConfiguration() {}

    public static RouteService fromEnvironment() {
        Map<String, String> settings = new HashMap<>();
        for (String name : new String[]{"ROUTE_PROVIDER", "GOONG_API_KEY", "GOONG_TIMEOUT_MS", "GOONG_VEHICLE", "ROUTE_MAX_DISTANCE_KM"}) {
            String value = System.getenv(name);
            if (value != null) settings.put(name, value);
        }
        return from(settings);
    }

    public static com.miniongvang.service.DriverRankingService driverRoutingFromEnvironment() {
        String key = System.getenv("GOONG_API_KEY");
        if (key == null || key.isBlank()) return new com.miniongvang.service.DriverRankingService(null);
        long timeout = positiveLong(System.getenv().getOrDefault("GOONG_TIMEOUT_MS", "5000"), "GOONG_TIMEOUT_MS");
        String vehicle = System.getenv().getOrDefault("GOONG_VEHICLE", "car").strip().toLowerCase(Locale.ROOT);
        return new com.miniongvang.service.DriverRankingService(new GoongRouteProvider(key, Duration.ofMillis(timeout), vehicle));
    }

    public static RouteService from(Map<String, String> env) {
        String name = env.getOrDefault("ROUTE_PROVIDER", "fake").strip().toLowerCase(Locale.ROOT);
        long maxKm = positiveLong(env.getOrDefault("ROUTE_MAX_DISTANCE_KM", "30"), "ROUTE_MAX_DISTANCE_KM");
        RouteProvider provider = switch (name) {
            case "fake" -> new FakeRouteProvider();
            case "goong" -> {
                String key = env.get("GOONG_API_KEY");
                if (key == null || key.isBlank()) throw new IllegalArgumentException("GOONG_API_KEY is required when ROUTE_PROVIDER=goong");
                long timeout = positiveLong(env.getOrDefault("GOONG_TIMEOUT_MS", "5000"), "GOONG_TIMEOUT_MS");
                String vehicle = env.getOrDefault("GOONG_VEHICLE", "car").strip().toLowerCase(Locale.ROOT);
                yield new GoongRouteProvider(key, Duration.ofMillis(timeout), vehicle);
            }
            default -> throw new IllegalArgumentException("ROUTE_PROVIDER must be fake or goong");
        };
        return new RouteService(provider, Math.multiplyExact(maxKm, 1000));
    }

    private static long positiveLong(String value, String name) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be a positive integer");
    }
}
