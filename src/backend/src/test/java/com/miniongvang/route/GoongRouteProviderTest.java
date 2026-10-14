package com.miniongvang.route;

import com.miniongvang.integration.GoongRouteProvider;
import com.miniongvang.integration.RouteFailure;
import com.miniongvang.integration.RouteProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoongRouteProviderTest {
    @Test void convertsGeocodeAndDirectionResponsesWithoutRealApiKey() throws Exception {
        AtomicInteger geocodes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/geocode", exchange -> {
            geocodes.incrementAndGet();
            assertTrue(exchange.getRequestURI().getRawQuery().contains("api_key=test-key"));
            reply(exchange, 200, """
                    {"results":[{"formatted_address":"Quận 1, TP.HCM","geometry":{"location":{"lat":10.77,"lng":106.7}}}]}
                    """);
        });
        server.createContext("/v2/direction", exchange -> {
            assertTrue(exchange.getRequestURI().getRawQuery().contains("vehicle=car"));
            reply(exchange, 200, """
                    {"routes":[{"legs":[{"distance":{"value":2828},"duration":{"value":615}}]}]}
                    """);
        });
        server.start();
        try {
            RouteProvider provider = new GoongRouteProvider(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"),
                    "test-key", Duration.ofSeconds(2));
            RouteProvider.RouteData route = provider.estimate("Quận 1", "Quận 3");
            assertEquals(2, geocodes.get());
            assertEquals(2828, route.distanceMeters());
            assertEquals(615, route.durationSeconds());
            assertEquals("GOONG", route.source());
        } finally { server.stop(0); }
    }

    @Test void missingGeocodeAndHttpFailureUseStableCodes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger status = new AtomicInteger(200);
        server.createContext("/v2/geocode", exchange ->
                reply(exchange, status.get(), status.get() == 200 ? "{\"results\":[]}" : "error"));
        server.start();
        try {
            RouteProvider provider = new GoongRouteProvider(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/"),
                    "test-key", Duration.ofSeconds(2));
            assertEquals(RouteFailure.Kind.ADDRESS_NOT_FOUND,
                    assertThrows(RouteFailure.class, () -> provider.estimate("A", "B")).kind());
            status.set(429);
            assertEquals(RouteFailure.Kind.ROUTE_PROVIDER_ERROR,
                    assertThrows(RouteFailure.class, () -> provider.estimate("A", "B")).kind());
        } finally { server.stop(0); }
    }

    @Test void driverDirectionsUseStoredCoordinatesWithoutGeocodingThemAgain() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger directions=new AtomicInteger();
        server.createContext("/v2/geocode",exchange->{fail("Stored coordinates must not geocode driver address again");reply(exchange,500,"{}");});
        server.createContext("/v2/direction",exchange->{
            String query=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);
            assertTrue(query.contains("origin=10.7700000,106.7000000"));
            assertTrue(query.contains("destination=10.7800000,106.7100000"));directions.incrementAndGet();
            reply(exchange,200,"{\"routes\":[{\"legs\":[{\"distance\":{\"value\":0},\"duration\":{\"value\":0}}]}]}");
        });server.start();
        try {
            var provider=new GoongRouteProvider(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v2/"),"test-key",Duration.ofSeconds(2));
            var origin=new com.miniongvang.integration.DriverRoutingProvider.Position("Stored address",new java.math.BigDecimal("10.77"),new java.math.BigDecimal("106.7"));
            var end=new com.miniongvang.integration.DriverRoutingProvider.Position("Pickup",new java.math.BigDecimal("10.78"),new java.math.BigDecimal("106.71"));
            assertEquals(0,provider.estimate(origin,end).distanceMeters());assertEquals(1,directions.get());
        } finally {server.stop(0);}
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
