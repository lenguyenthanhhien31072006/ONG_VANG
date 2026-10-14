package com.miniongvang.service;

import com.miniongvang.dto.dispatch.DispatchDtos.Driver;
import com.miniongvang.integration.DriverRoutingProvider;
import com.miniongvang.integration.DriverRoutingProvider.Position;
import com.miniongvang.integration.RouteFailure;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** External routing is performed on immutable snapshots, never inside a DB transaction. */
public final class DriverRankingService {
    public static final String ATTRIBUTE = DriverRankingService.class.getName();
    private static final int MAX_ROUTE_CANDIDATES = 50;
    private final DriverRoutingProvider provider;
    public DriverRankingService(DriverRoutingProvider provider) { this.provider = provider; }
    public record Candidate(Driver driver, Instant idleAt) {}
    public record Ranked(List<Driver> items, String method, String fallbackReason) {}
    private record Measured(Driver driver, long meters) {}

    public Position verify(String address) {
        String normalized = address == null ? "" : address.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 255) throw new IllegalArgumentException("Invalid address");
        if (provider == null) throw new Unconfigured();
        return provider.geocode(normalized);
    }

    public Ranked rank(List<Candidate> candidates, String pickup) {
        if (candidates.isEmpty()) return new Ranked(List.of(), "KHONG_CO_TAI_XE", null);
        if (provider == null) return fallback(candidates, "GOONG_NOT_CONFIGURED");
        if (candidates.stream().anyMatch(c -> c.driver().viDoDemo() == null || c.driver().kinhDoDemo() == null
                || c.driver().diaChiViTriDemo() == null || !"DEMO_CO_DINH".equals(c.driver().nguonViTri())
                || c.driver().viTriCapNhatLuc() == null)) return fallback(candidates, "DRIVER_LOCATION_MISSING");
        if (candidates.size() > MAX_ROUTE_CANDIDATES) return fallback(candidates, "ROUTING_CANDIDATE_LIMIT");
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            Position destination = provider.geocode(pickup);
            var measured = new java.util.ArrayList<Measured>();
            for (Candidate candidate : candidates) {
                if (System.nanoTime() > deadline) return fallback(candidates, "ROUTE_PROVIDER_TIMEOUT");
                Driver d = candidate.driver();
                var route = provider.estimate(new Position(d.diaChiViTriDemo(), new BigDecimal(d.viDoDemo()),
                        new BigDecimal(d.kinhDoDemo())), destination);
                if (!"GOONG".equals(route.source()) || route.distanceMeters() < 0 || route.durationSeconds() < 0
                        || route.durationSeconds() > Integer.MAX_VALUE)
                    return fallback(candidates, "ROUTE_PROVIDER_ERROR");
                measured.add(new Measured(withRoute(d, BigDecimal.valueOf(route.distanceMeters(), 3).toPlainString(),
                        route.durationSeconds(), route.source()), route.distanceMeters()));
            }
            if (System.nanoTime() > deadline) return fallback(candidates, "ROUTE_PROVIDER_TIMEOUT");
            measured.sort(Comparator.comparingLong(Measured::meters).thenComparing(m -> m.driver().maTx()));
            return new Ranked(measured.stream().map(Measured::driver).toList(), "GOONG_DEN_DIEM_LAY", null);
        } catch (RouteFailure failure) { return fallback(candidates, failure.kind().name()); }
    }

    public static Ranked fallback(List<Candidate> candidates, String reason) {
        return new Ranked(candidates.stream().sorted(Comparator.comparing(Candidate::idleAt,
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(c -> c.driver().maTx()))
                .map(c -> withRoute(c.driver(), null, null, null)).toList(), "THOI_GIAN_RANH_DU_PHONG", reason);
    }
    private static Driver withRoute(Driver d, String km, Long seconds, String source) {
        return new Driver(d.maTx(),d.hoTen(),d.soDienThoai(),d.trangThaiHoatDong(),d.dangBanChuyen(),d.phuongTien(),
                d.diaChiViTriDemo(),d.viDoDemo(),d.kinhDoDemo(),d.nguonViTri(),d.viTriCapNhatLuc(),km,seconds,source);
    }
    public static final class Unconfigured extends RuntimeException {}
}
