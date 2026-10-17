package com.miniongvang.controller;

import com.miniongvang.config.PersistenceContext;
import com.miniongvang.config.PersistenceListener;
import com.miniongvang.dto.auth.AuthDtos.User;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.security.AuthSession;
import com.miniongvang.service.AuthService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

/** Role gate shared by future business servlets; ownership remains each service's responsibility. */
@WebFilter("/api/*")
public final class ApiSecurityFilter implements Filter {
    private static final Set<String> ORIGINS = Set.of("http://localhost:3000", "http://localhost:3001");
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/health", "/api/auth/csrf", "/api/auth/login", "/api/auth/register",
            "/api/auth/forgot-password", "/api/auth/verify-reset-otp", "/api/auth/reset-password",
            "/api/auth/logout", "/api/auth/me", "/api/payments/vnpay/ipn", "/api/payments/vnpay/return");

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        resp.setHeader("Cache-Control", "no-store");
        String origin = req.getHeader("Origin");
        if (origin != null && ORIGINS.contains(origin)) {
            resp.setHeader("Access-Control-Allow-Origin", origin);
            resp.setHeader("Access-Control-Allow-Credentials", "true");
            resp.setHeader("Vary", "Origin");
        }
        if ("OPTIONS".equals(req.getMethod())) {
            if (origin == null || !ORIGINS.contains(origin)) { resp.setStatus(403); return; }
            resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token, Idempotency-Key");
            resp.setStatus(204);
            return;
        }
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        if (PUBLIC_PATHS.contains(path)) {
            chain.doFilter(request, response); return;
        }
        EnumSet<AccountRole> roles = roles(req.getMethod(), path);
        HttpSession session = req.getSession(false);
        String id = AuthSession.userId(session);
        AuthService auth = null;
        if (id != null) {
            PersistenceContext persistence = (PersistenceContext) req.getServletContext().getAttribute(PersistenceListener.ATTRIBUTE);
            auth = new AuthService(persistence.entityManagerFactory());
            if (!auth.sessionValid(id, AuthSession.version(session))) {
                session.invalidate(); error(resp, 401, "SESSION_EXPIRED"); return;
            }
        }
        if (roles == null) { chain.doFilter(request, response); return; }
        if (id == null) { error(resp, 401, AuthSession.missingSessionCode(req)); return; }
        User user = auth.current(id);
        if (user == null) { session.invalidate(); error(resp, 403, "FORBIDDEN"); return; }
        if (!roles.contains(user.vaiTro())) { error(resp, 403, "FORBIDDEN"); return; }
        if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
            if (!AuthSession.hasValidCsrf(req, session)) {
                error(resp, 403, "CSRF_INVALID"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private static EnumSet<AccountRole> roles(String method, String path) {
        if (path.equals("/api/customers") || path.equals("/api/drivers") || path.matches("/api/orders/[^/]+/driver-suggestions"))
            return EnumSet.of(AccountRole.TONG_DAI);
        if (path.equals("/api/drivers/me/earnings")) return EnumSet.of(AccountRole.TAI_XE);
        if (path.equals("/api/reports/drivers")) return EnumSet.of(AccountRole.CHU_DOI_XE);
        if (path.startsWith("/api/reports/")) return EnumSet.of(AccountRole.CHU_DOI_XE, AccountRole.TONG_DAI);
        if (path.matches("/api/orders/[^/]+/(reject|transitions|incidents)")) return EnumSet.of(AccountRole.TAI_XE);
        if (path.matches("/api/orders/[^/]+/assignments")) return EnumSet.of(AccountRole.TONG_DAI);
        if (path.matches("/api/orders/[^/]+/cancel") || path.equals("/api/routes/estimate") || path.equals("/api/quotes"))
            return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI);
        if (path.matches("/api/orders/[^/]+/payments") && "POST".equals(method)) return EnumSet.of(AccountRole.TAI_XE);
        if (path.matches("/api/payments/[^/]+/confirm-cash")) return EnumSet.of(AccountRole.TAI_XE);
        if (path.matches("/api/payments/[^/]+/reconcile")) return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI, AccountRole.TAI_XE);
        if (path.equals("/api/orders") && "POST".equals(method)) return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI);
        if (path.equals("/api/orders") || path.matches("/api/orders/[^/]+(/events|/payments)?") || path.matches("/api/payments/[^/]+"))
            return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI, AccountRole.TAI_XE);
        return null;
    }

    private static void error(HttpServletResponse resp, int status, String code) throws IOException {
        com.miniongvang.web.WebResponses.error(resp, status, code);
    }
}
