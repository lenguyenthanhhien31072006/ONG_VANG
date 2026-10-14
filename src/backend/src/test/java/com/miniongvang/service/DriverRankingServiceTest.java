package com.miniongvang.service;

import com.miniongvang.dto.dispatch.DispatchDtos.Driver;
import com.miniongvang.integration.DriverRoutingProvider;
import com.miniongvang.integration.RouteFailure;
import com.miniongvang.integration.RouteProvider;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DriverRankingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static DriverRankingService.Candidate driver(String id, long idleSeconds, boolean located) {
        return new DriverRankingService.Candidate(new Driver(id,id,"0901234567","ONLINE",false,null,
                located ? "Địa chỉ " + id : null,located ? "10.7700000" : null,located ? "106.7000000" : null,
                located ? "DEMO_CO_DINH" : null,located ? NOW.toString() : null,null,null,null),NOW.minusSeconds(idleSeconds));
    }
    private static DriverRoutingProvider.Position position(String address) {
        return new DriverRoutingProvider.Position(address,new BigDecimal("10.77"),new BigDecimal("106.7"));
    }
    @Test void routesFromStoredDriverCoordinatesToPickupAndSortsAcrossAllCandidates() {
        AtomicInteger lookups = new AtomicInteger();
        var service = new DriverRankingService(new DriverRoutingProvider() {
            public Position geocode(String address) { lookups.incrementAndGet(); assertEquals("Điểm lấy hàng",address); return position(address); }
            public RouteProvider.RouteData estimate(Position origin,Position destination) {
                assertEquals("Điểm lấy hàng",destination.address());
                assertEquals("10.7700000",origin.latitude().toPlainString());
                long meters = origin.address().endsWith("TX2") ? 1234 : 3000;
                return new RouteProvider.RouteData(origin.address(),destination.address(),meters,100,"GOONG");
            }
        });
        var result = service.rank(List.of(driver("TX1",300,true),driver("TX3",100,true),driver("TX2",50,true)),"Điểm lấy hàng");
        assertEquals(List.of("TX2","TX1","TX3"),result.items().stream().map(Driver::maTx).toList());
        assertEquals("1.234",result.items().getFirst().quangDuongDenDiemLayKm());
        assertEquals("GOONG",result.items().getFirst().nguonLoTrinh());
        assertEquals("GOONG_DEN_DIEM_LAY",result.method()); assertNull(result.fallbackReason()); assertEquals(1,lookups.get());
    }
    @Test void missingKeyAndMissingPositionUseExplicitFallbackWithoutPretendingToBeGoong() {
        var candidates = List.of(driver("TX1",100,true),driver("TX2",200,false));
        var missingKey = new DriverRankingService(null).rank(candidates,"Pickup");
        assertEquals("GOONG_NOT_CONFIGURED",missingKey.fallbackReason());
        assertEquals(List.of("TX2","TX1"),missingKey.items().stream().map(Driver::maTx).toList());
        assertNull(missingKey.items().getFirst().nguonLoTrinh());
        assertThrows(DriverRankingService.Unconfigured.class,() -> new DriverRankingService(null).verify("Hồ Chí Minh"));
        var unused = new DriverRankingService(new DriverRoutingProvider() {
            public Position geocode(String address) { fail("Missing position must not make a paid call"); return null; }
            public RouteProvider.RouteData estimate(Position a,Position b) { fail(); return null; }
        }).rank(candidates,"Pickup");
        assertEquals("DRIVER_LOCATION_MISSING",unused.fallbackReason());
    }
    @Test void partialProviderFailureDiscardsAllDistancesAndPreservesLabeledIdleFallback() {
        var service = new DriverRankingService(new DriverRoutingProvider() {
            public Position geocode(String address) { return position(address); }
            public RouteProvider.RouteData estimate(Position a,Position b) {
                if (a.address().endsWith("TX2")) throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_TIMEOUT);
                return new RouteProvider.RouteData(a.address(),b.address(),1000,30,"GOONG");
            }
        });
        var result = service.rank(List.of(driver("TX1",100,true),driver("TX2",200,true)),"Pickup");
        assertEquals("THOI_GIAN_RANH_DU_PHONG",result.method()); assertEquals("ROUTE_PROVIDER_TIMEOUT",result.fallbackReason());
        assertTrue(result.items().stream().allMatch(d -> d.quangDuongDenDiemLayKm()==null && d.nguonLoTrinh()==null));
    }
    @Test void zeroDistanceAtPickupIsValidAndPositionValidationRejectsInvalidCoordinates() {
        var service = new DriverRankingService(new DriverRoutingProvider() {
            public Position geocode(String address) { return position(address); }
            public RouteProvider.RouteData estimate(Position a,Position b) { return new RouteProvider.RouteData(a.address(),b.address(),0,0,"GOONG"); }
        });
        var result = service.rank(List.of(driver("TX1",1,true)),"Pickup");
        assertEquals("0.000",result.items().getFirst().quangDuongDenDiemLayKm());
        assertEquals(0,result.items().getFirst().thoiGianDenDiemLayGiay());
        assertThrows(IllegalArgumentException.class,() -> position(""));
        assertThrows(IllegalArgumentException.class,() -> new DriverRoutingProvider.Position("A",new BigDecimal("91"),BigDecimal.ZERO));
    }
    @Test void fabricatedRouteSourceCannotBeLabeledGoong() {
        var service=new DriverRankingService(new DriverRoutingProvider() {
            public Position geocode(String address) {return position(address);}
            public RouteProvider.RouteData estimate(Position a,Position b) {return new RouteProvider.RouteData(a.address(),b.address(),500,60,"FAKE");}
        });
        var result=service.rank(List.of(driver("TX1",1,true)),"Pickup");
        assertEquals("ROUTE_PROVIDER_ERROR",result.fallbackReason());assertNull(result.items().getFirst().quangDuongDenDiemLayKm());
    }

}
