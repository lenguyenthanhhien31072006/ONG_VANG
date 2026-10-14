package com.miniongvang.controller;
import com.miniongvang.dto.auth.AuthDtos.User;

import com.miniongvang.config.PersistenceContext;
import com.miniongvang.config.PersistenceListener;
import com.miniongvang.entity.enums.AccountRole;
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
        if (path.equals("/api/health") || path.equals("/api/auth/csrf") || path.equals("/api/auth/login")
                || path.equals("/api/auth/register") || path.equals("/api/payments/vnpay/ipn")
                || path.equals("/api/payments/vnpay/return") || path.equals("/api/auth/logout")
                || path.equals("/api/auth/me")) {
            chain.doFilter(request, response); return;
        }
        EnumSet<AccountRole> roles = roles(req.getMethod(), path);
        if (roles == null) { chain.doFilter(request, response); return; }
        HttpSession session = req.getSession(false);
        String id = session == null ? null : (String) session.getAttribute("auth.userId");
        if (id == null) { error(resp, 401, missingSessionCode(req)); return; }
        PersistenceContext persistence = (PersistenceContext) req.getServletContext().getAttribute(PersistenceListener.ATTRIBUTE);
        User user = new AuthService(persistence.entityManagerFactory()).current(id);
        if (user == null) { session.invalidate(); error(resp, 403, "FORBIDDEN"); return; }
        if (!roles.contains(user.vaiTro())) { error(resp, 403, "FORBIDDEN"); return; }
        if (!"GET".equals(req.getMethod()) && !"HEAD".equals(req.getMethod())) {
            String token = (String) session.getAttribute("auth.csrf");
            String supplied = req.getHeader("X-CSRF-Token");
            if (token == null || supplied == null || !java.security.MessageDigest.isEqual(
                    token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                error(resp, 403, "CSRF_INVALID"); return;
            }
        }
        chain.doFilter(request, response);
    }

    private static EnumSet<AccountRole> roles(String method, String path) {
        if (path.equals("/api/customers") || path.equals("/api/drivers") || path.matches("/api/drivers/[^/]+/demo-location") || path.matches("/api/orders/[^/]+/driver-suggestions"))
            return EnumSet.of(AccountRole.TONG_DAI);
        if (path.startsWith("/api/reports/")) return EnumSet.of(AccountRole.CHU_DOI_XE);
        if (path.matches("/api/orders/[^/]+/(reject|transitions|incidents)")) return EnumSet.of(AccountRole.TAI_XE);
        if (path.matches("/api/orders/[^/]+/assignments")) return EnumSet.of(AccountRole.TONG_DAI);
        if (path.matches("/api/orders/[^/]+/cancel") || path.equals("/api/routes/estimate") || path.equals("/api/quotes"))
            return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI);
        if (path.matches("/api/orders/[^/]+/payments") && "POST".equals(method)) return EnumSet.of(AccountRole.KHACH_HANG);
        if (path.matches("/api/payments/[^/]+/confirm-cash")) return EnumSet.of(AccountRole.TAI_XE, AccountRole.TONG_DAI);
        if (path.matches("/api/payments/[^/]+/reconcile")) return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI);
        if (path.equals("/api/orders") && "POST".equals(method)) return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI);
        if (path.equals("/api/orders") || path.matches("/api/orders/[^/]+(/events|/payments)?") || path.matches("/api/payments/[^/]+"))
            return EnumSet.of(AccountRole.KHACH_HANG, AccountRole.TONG_DAI, AccountRole.TAI_XE);
        return null;
    }

    private static void error(HttpServletResponse resp, int status, String code) throws IOException {
        com.miniongvang.web.WebResponses.error(resp, status, code);
    }
    private static String missingSessionCode(HttpServletRequest req) {
        if (req.getCookies() != null) for (var cookie : req.getCookies())
            if ("JSESSIONID".equals(cookie.getName())) return "SESSION_EXPIRED";
        return "AUTH_REQUIRED";
    }
}
