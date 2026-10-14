package com.miniongvang.config;

import com.miniongvang.seed.DesignV1DemoSeeder;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class PersistenceListener implements ServletContextListener {
    public static final String ATTRIBUTE = PersistenceContext.class.getName();

    @Override public void contextInitialized(ServletContextEvent event) {
        event.getServletContext().getSessionCookieConfig().setHttpOnly(true);
        event.getServletContext().getSessionCookieConfig().setPath("/");
        event.getServletContext().setSessionTrackingModes(java.util.Set.of(jakarta.servlet.SessionTrackingMode.COOKIE));
        PersistenceContext persistence = PersistenceContext.start(
                System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
        try {
            if ("true".equalsIgnoreCase(System.getenv("DEMO_SEED"))) {
                String password = System.getenv("DEMO_PASSWORD");
                if (password == null || password.isBlank()) {
                    throw new IllegalArgumentException("DEMO_PASSWORD is required when DEMO_SEED=true");
                }
                new DesignV1DemoSeeder(persistence.entityManagerFactory()).seed(password);
            }
            event.getServletContext().setAttribute(RouteConfiguration.ATTRIBUTE, RouteConfiguration.fromEnvironment());
            event.getServletContext().setAttribute(com.miniongvang.service.DriverRankingService.ATTRIBUTE,
                    RouteConfiguration.driverRoutingFromEnvironment());
            event.getServletContext().setAttribute(ATTRIBUTE, persistence);
        } catch (RuntimeException failure) {
            persistence.close();
            throw failure;
        }
    }

    @Override public void contextDestroyed(ServletContextEvent event) {
        PersistenceContext persistence = (PersistenceContext) event.getServletContext().getAttribute(ATTRIBUTE);
        if (persistence != null) persistence.close();
    }
}
