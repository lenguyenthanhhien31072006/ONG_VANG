package com.miniongvang.integration;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Goong V2 geocoding and directions; API-specific JSON stays behind RouteProvider. */
public final class GoongRouteProvider implements RouteProvider, DriverRoutingProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(GoongRouteProvider.class.getName());
    private final HttpClient client;
    private final URI baseUri;
    private final String apiKey;
    private final String vehicle;
    private final Duration timeout;

    public GoongRouteProvider(String apiKey, Duration timeout) {
        this(apiKey, timeout, "car");
    }

    public GoongRouteProvider(String apiKey, Duration timeout, String vehicle) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(),
                URI.create("https://rsapi.goong.io/v2/"), apiKey, timeout, vehicle);
    }

    /** Injectable transport and base URL let tests exercise the adapter without a real key. */
    public GoongRouteProvider(HttpClient client, URI baseUri, String apiKey, Duration timeout) {
        this(client, baseUri, apiKey, timeout, "car");
    }

    public GoongRouteProvider(HttpClient client, URI baseUri, String apiKey, Duration timeout, String vehicle) {
        if (client == null || baseUri == null || apiKey == null || apiKey.isBlank()
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || vehicle == null || !java.util.Set.of("car", "bike", "motorcycle", "truck").contains(vehicle))
            throw new IllegalArgumentException("Invalid Goong configuration");
        this.client = client;
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.vehicle = vehicle;
        this.timeout = timeout;
    }

    @Override public RouteData estimate(String origin, String destination) {
        Position start = geocode(origin);
        Position end = geocode(destination);
        RouteData route = estimate(start, end);
        if (route.distanceMeters() <= 0) throw new RouteFailure(RouteFailure.Kind.DISTANCE_INVALID);
        return route;
    }

    @Override public RouteData estimate(Position start, Position end) {
        JsonNode response = get("direction?origin=" + encode(start.coordinates())
                + "&destination=" + encode(end.coordinates()) + "&vehicle=" + vehicle + "&api_key=" + encode(apiKey));
        JsonNode routes = response.path("routes");
        if (!routes.isArray() || routes.isEmpty())
            throw new RouteFailure(RouteFailure.Kind.ADDRESS_NOT_FOUND);
        JsonNode leg = routes.get(0).path("legs");
        if (!leg.isArray() || leg.isEmpty()) throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        long meters = nonnegativeLong(leg.get(0).path("distance").path("value"));
        long seconds = nonnegativeLong(leg.get(0).path("duration").path("value"));
        return new RouteData(start.address(), end.address(), meters, seconds, "GOONG");
    }

    @Override public Position geocode(String address) {
        JsonNode response = get("geocode?address=" + encode(address) + "&api_key=" + encode(apiKey));
        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty())
            throw new RouteFailure(RouteFailure.Kind.ADDRESS_NOT_FOUND);
        JsonNode first = results.get(0);
        JsonNode position = first.path("geometry").path("location");
        String formatted = first.path("formatted_address").asText();
        if (formatted.isBlank() || formatted.length() > 255
                || !position.path("lat").isNumber() || !position.path("lng").isNumber())
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        double lat = position.path("lat").asDouble();
        double lng = position.path("lng").asDouble();
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180)
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        return new Position(formatted, java.math.BigDecimal.valueOf(lat), java.math.BigDecimal.valueOf(lng));
    }

    private JsonNode get(String pathAndQuery) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(pathAndQuery))
                    .timeout(timeout).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warning("Goong API returned HTTP " + response.statusCode());
                throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
            }
            try {
                JsonNode body = JSON.readTree(response.body());
                if (body == null || !body.isObject()) throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
                return body;
            } catch (RouteFailure failure) {
                throw failure;
            } catch (RuntimeException invalidJson) {
                throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
            }
        } catch (java.net.http.HttpTimeoutException failure) {
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_TIMEOUT);
        } catch (IOException failure) {
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        } catch (IllegalArgumentException failure) {
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        }
    }

    private static long nonnegativeLong(JsonNode value) {
        if (!value.isNumber()) throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        double number = value.asDouble();
        if (!Double.isFinite(number) || number < 0 || number > Long.MAX_VALUE)
            throw new RouteFailure(RouteFailure.Kind.ROUTE_PROVIDER_ERROR);
        return Math.round(number);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
