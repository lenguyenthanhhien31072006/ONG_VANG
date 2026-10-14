package com.miniongvang.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Verified geocoding and road routing for fixed demonstration driver positions. */
public interface DriverRoutingProvider {
    Position geocode(String address);
    RouteProvider.RouteData estimate(Position origin, Position destination);

    record Position(String address, BigDecimal latitude, BigDecimal longitude) {
        public Position {
            if (address == null || address.isBlank() || address.length() > 255
                    || latitude == null || longitude == null
                    || latitude.abs().compareTo(new BigDecimal("90")) > 0
                    || longitude.abs().compareTo(new BigDecimal("180")) > 0)
                throw new IllegalArgumentException("Invalid position");
            latitude = latitude.setScale(7, RoundingMode.HALF_UP);
            longitude = longitude.setScale(7, RoundingMode.HALF_UP);
        }
        public String coordinates() { return latitude.toPlainString() + "," + longitude.toPlainString(); }
    }
}
