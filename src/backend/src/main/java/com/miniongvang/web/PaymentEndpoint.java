package com.miniongvang.web;
import com.miniongvang.dto.payment.PaymentDtos.IpnResponse;
import com.miniongvang.dto.payment.PaymentDtos.CreatePaymentRequest;
import com.miniongvang.dto.payment.PaymentDtos.ConfirmCashRequest;

import com.miniongvang.config.PersistenceContext;
import com.miniongvang.config.PersistenceListener;
import com.miniongvang.service.AuthService;
import com.miniongvang.service.PaymentService;
import com.miniongvang.service.VnpayPaymentService;
import com.miniongvang.integration.VnpayGateway;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import tools.jackson.databind.JsonNode;

/** Shared HTTP handler for payment URLs under both /orders and /payments. */
public final class PaymentEndpoint {
    private PaymentEndpoint() {}

    public static void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        try {
            PersistenceContext persistence = (PersistenceContext) req.getServletContext().getAttribute(PersistenceListener.ATTRIBUTE);
            var service = new PaymentService(persistence.entityManagerFactory());
            HttpSession session = req.getSession(false);
            String user = session == null ? null : (String) session.getAttribute("auth.userId");
            var actor = user == null ? null : new AuthService(persistence.entityManagerFactory()).currentActor(user);
            String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
            String[] parts = path.split("/", -1);
            boolean get = "GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
            boolean post = "POST".equals(req.getMethod());
            if (path.equals("/api/payments/vnpay/ipn") && "GET".equals(req.getMethod())) {
                var result = new VnpayPaymentService(persistence.entityManagerFactory(), VnpayGateway.fromEnvironment())
                        .ipn(req.getQueryString());
                String code = switch (result.result()) {
                    case KHONG_HOP_LE -> "97";
                    case KHONG_KHOP -> "01";
                    default -> result.duplicate() ? "02" : "00";
                };
                send(resp, 200, new IpnResponse(code, result.result().name()));
                return;
            }
            if (path.equals("/api/payments/vnpay/return") && "GET".equals(req.getMethod())) {
                var gateway = VnpayGateway.fromEnvironment();
                var result = new VnpayPaymentService(persistence.entityManagerFactory(), gateway)
                        .browserReturn(req.getQueryString());
                if (result.returnOrderId() != null) {
                    String target = System.getenv("DRIVER_RETURN_URL");
                    if (target == null || target.isBlank()) target = "http://localhost:3001/driver/orders";
                    if (!target.matches("https?://[^?#]+/driver/orders"))
                        throw new IllegalStateException("Invalid driver return URL");
                    resp.sendRedirect(target + "/" + result.returnOrderId());
                    return;
                }
                resp.setStatus(200);
                resp.setContentType("text/html;charset=UTF-8");
                resp.getWriter().write("<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\"><title>VNPay</title>"
                        + "<p>Đã nhận phản hồi từ VNPay. Vui lòng kiểm tra trạng thái thanh toán trong ứng dụng.</p></html>");
                return;
            }
            if (parts.length == 5 && parts[2].equals("orders") && parts[4].equals("payments")) {
                if (get) send(resp, 200, service.list(actor, parts[3], integer(req, "page", 0), integer(req, "size", 20)));
                else if (post) {
                    JsonNode body = body(req, "phuongThuc");
                    var command = new CreatePaymentRequest(com.miniongvang.entity.enums.PaymentMethod.valueOf(
                            body.get("phuongThuc").textValue()));
                    if (command.phuongThuc() != com.miniongvang.entity.enums.PaymentMethod.TIEN_MAT
                            && command.phuongThuc() != com.miniongvang.entity.enums.PaymentMethod.VNPAY_QR)
                        throw new IllegalArgumentException("Invalid payment method");
                    send(resp, 201, command.phuongThuc() == com.miniongvang.entity.enums.PaymentMethod.VNPAY_QR
                            ? service.createOnline(actor, parts[3], req.getHeader("Idempotency-Key"),
                                    req.getRemoteAddr(), VnpayGateway.fromEnvironment())
                            : service.createCash(actor, parts[3], req.getHeader("Idempotency-Key")));
                } else throw new NoSuchElementException();
            } else if (parts.length == 4 && parts[2].equals("payments") && get) {
                send(resp, 200, service.get(actor, parts[3]));
            } else if (parts.length == 5 && parts[2].equals("payments")
                    && parts[4].equals("confirm-cash") && post) {
                JsonNode body = body(req, "soTienDaThu");
                BigDecimal amount;
                var command = new ConfirmCashRequest(body.get("soTienDaThu").textValue());
                try { amount = new BigDecimal(command.soTienDaThu()); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid amount"); }
                var result = service.confirmCash(actor, parts[3], amount);
                send(resp, result.canDoiSoat() ? 422 : 200, result.canDoiSoat()
                        ? com.miniongvang.dto.common.ApiErrorResponse.of("PAYMENT_AMOUNT_MISMATCH", "Khoan thu can doi soat")
                        : result.payment());
            } else if (parts.length == 5 && parts[2].equals("payments")
                    && parts[4].equals("reconcile") && post) {
                send(resp, 200, new VnpayPaymentService(persistence.entityManagerFactory(), VnpayGateway.fromEnvironment())
                        .reconcile(actor, parts[3]));
            } else throw new NoSuchElementException();
        } catch (JsonRequests.InvalidJson invalid) { error(resp, 400, "JSON_INVALID"); }
        catch (IllegalStateException unavailable) {
            if ("VNPAY_CONFIG_MISSING".equals(unavailable.getMessage())) error(resp, 503, "VNPAY_CONFIG_MISSING");
            else { req.getServletContext().log("Payment request failed", unavailable); error(resp, 500, "INTERNAL_ERROR"); }
        }
        catch (IllegalArgumentException invalid) { error(resp, 400, "VALIDATION_ERROR"); }
        catch (PaymentService.NotRequired invalid) { error(resp, 422, "PAYMENT_NOT_REQUIRED"); }
        catch (PaymentService.Conflict conflict) { error(resp, 409, conflict.getMessage()); }
        catch (SecurityException forbidden) { error(resp, 403, "FORBIDDEN"); }
        catch (NoSuchElementException missing) { error(resp, 404, "RESOURCE_NOT_FOUND"); }
        catch (RuntimeException failure) {
            req.getServletContext().log("Payment request failed", failure);
            if (!resp.isCommitted()) error(resp, 500, "INTERNAL_ERROR");
        }
    }

    private static int integer(HttpServletRequest req, String name, int fallback) {
        String[] values = req.getParameterValues(name);
        if (values == null) return fallback;
        if (values.length != 1) throw new IllegalArgumentException("Invalid pagination");
        return Integer.parseInt(values[0]);
    }


    private static JsonNode body(HttpServletRequest req, String field) throws IOException {
        JsonNode data = JsonRequests.object(req);
        if (data.size() != 1 || !data.has(field) || !data.get(field).isTextual())
            throw new IllegalArgumentException("Invalid fields");
        return data;
    }

    private static void send(HttpServletResponse resp, int status, Object value) throws IOException {
        com.miniongvang.web.WebResponses.json(resp, status, value);
    }
    private static void error(HttpServletResponse resp, int status, String code) throws IOException {
        com.miniongvang.web.WebResponses.error(resp, status, code, "Khong the thuc hien yeu cau: " + code);
    }
}
