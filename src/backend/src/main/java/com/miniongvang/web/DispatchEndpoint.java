package com.miniongvang.web;

import com.miniongvang.config.PersistenceContext;
import com.miniongvang.config.PersistenceListener;
import com.miniongvang.dto.dispatch.DispatchDtos.AssignmentRequest;
import com.miniongvang.dto.dispatch.DispatchDtos.IncidentRequest;
import com.miniongvang.dto.dispatch.DispatchDtos.ReasonRequest;
import com.miniongvang.dto.dispatch.DispatchDtos.TransitionRequest;
import com.miniongvang.entity.enums.*;
import com.miniongvang.service.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** HTTP handling shared by order collection, order details and driver URLs. */
public final class DispatchEndpoint {
    private DispatchEndpoint() {}

    public static void handle(HttpServletRequest req,HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control","no-store");
        String requestPath=req.getServletPath()+(req.getPathInfo()==null?"":req.getPathInfo());
        if (requestPath.matches("/api/orders/[^/]+/payments")) {
            com.miniongvang.web.PaymentEndpoint.handle(req,resp);
            return;
        }
        try {
            var persistence=(PersistenceContext)req.getServletContext().getAttribute(PersistenceListener.ATTRIBUTE);
            var routing=(com.miniongvang.service.DriverRankingService)req.getServletContext()
                    .getAttribute(com.miniongvang.service.DriverRankingService.ATTRIBUTE);
            var service=new DispatchService(persistence.entityManagerFactory(),java.time.Clock.systemUTC(),
                    routing==null?new com.miniongvang.service.DriverRankingService(null):routing);
            HttpSession session=req.getSession(false);
            String user=session==null?null:(String)session.getAttribute("auth.userId");
            var actor=user==null?null:new AuthService(persistence.entityManagerFactory()).currentActor(user);
            String path=req.getServletPath()+(req.getPathInfo()==null?"":req.getPathInfo());
            // HttpServlet routes HEAD through doGet. HEAD must never reach writes
            // (the security filter intentionally exempts safe methods from CSRF).
            boolean get="GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod());
            boolean post="POST".equals(req.getMethod());
            Object result;
            int responseStatus=200;
            if (get && path.equals("/api/orders")) {
                result=service.orders(actor,integer(req,"page",0),integer(req,"size",20),
                        date(req,"tuNgay"),date(req,"denNgay"),enumeration(req,"trangThai",OrderStatus.class));
            } else if (get && path.equals("/api/drivers")) {
                result=service.drivers(actor,integer(req,"page",0),integer(req,"size",20),
                        enumeration(req,"trangThaiHoatDong",DriverStatus.class),bool(req,"dangBanChuyen"));
            } else if (post && path.matches("/api/drivers/[^/]+/demo-location")) {
                result=service.configureLocation(actor,path.split("/",-1)[3],body(req,"diaChi"));
            } else {
                String[] parts=path.split("/",-1);
                if (parts.length<4 || !parts[1].equals("api") || !parts[2].equals("orders"))
                    throw new NoSuchElementException("Endpoint not found");
                String id=parts[3];
                if (get && parts.length==4) result=service.detail(actor,id);
                else if (get && parts.length==5 && parts[4].equals("events"))
                    result=service.events(actor,id,integer(req,"page",0),integer(req,"size",20));
                else if (post && parts.length==5 && parts[4].equals("transitions")) {
                    var command=new TransitionRequest(OrderStatus.valueOf(body(req,"trangThai")));
                    result=service.transition(actor,id,command.trangThai());
                }
                else if (post && parts.length==5 && parts[4].equals("incidents")) {
                    JsonNode incident=bodyFields(req,"loaiSuCo","lyDo");
                    var command=new IncidentRequest(IncidentType.valueOf(incident.get("loaiSuCo").textValue()),
                            incident.get("lyDo").textValue());
                    result=service.incident(actor,id,command.loaiSuCo(),command.lyDo());
                    responseStatus=201;
                }
                else if (get && parts.length==5 && parts[4].equals("driver-suggestions"))
                    result=service.suggestions(actor,id,integer(req,"page",0),integer(req,"size",20));
                else if (post && parts.length==5 && parts[4].equals("assignments")) {
                    var command=new AssignmentRequest(body(req,"maTx"));
                    result=service.assign(actor,id,command.maTx());
                }
                else if (post && parts.length==5 && parts[4].equals("reject")) {
                    var command=new ReasonRequest(body(req,"lyDo"));
                    result=service.reject(actor,id,command.lyDo());
                }
                else if (post && parts.length==5 && parts[4].equals("cancel")) {
                    var command=new ReasonRequest(body(req,"lyDo"));
                    result=service.cancel(actor,id,command.lyDo());
                }
                else throw new NoSuchElementException("Endpoint not found");
            }
            send(resp,responseStatus,result);
        } catch (JsonRequests.InvalidJson invalid) { error(resp,400,"JSON_INVALID"); }
        catch (DispatchErrors.DateRangeInvalid invalid) { error(resp,400,"DATE_RANGE_INVALID"); }
        catch (IllegalArgumentException invalid) { error(resp,400,"VALIDATION_ERROR"); }
        catch (SecurityException forbidden) { error(resp,403,"FORBIDDEN"); }
        catch (NoSuchElementException missing) { error(resp,404,"RESOURCE_NOT_FOUND"); }
        catch (DispatchErrors.Conflict conflict) { error(resp,409,conflict.getMessage()); }
        catch (com.miniongvang.service.DriverRankingService.Unconfigured unavailable) { error(resp,503,"GOONG_NOT_CONFIGURED"); }
        catch (com.miniongvang.integration.RouteFailure failure) {
            int status=switch(failure.kind()) {
                case ADDRESS_NOT_FOUND,OUT_OF_SERVICE_AREA,DISTANCE_INVALID -> 422;
                case ROUTE_PROVIDER_TIMEOUT -> 504;
                case ROUTE_PROVIDER_ERROR -> 502;
            };
            error(resp,status,failure.kind().name());
        }
        catch (RuntimeException failure) {
            req.getServletContext().log("Dispatch request failed",failure);
            if (!resp.isCommitted()) error(resp,500,"INTERNAL_ERROR");
        }
    }
    private static String parameter(HttpServletRequest req,String name) {
        String[] values=req.getParameterValues(name);
        if (values==null) return null;
        if (values.length!=1 || values[0].isBlank()) throw new IllegalArgumentException("Invalid query parameter");
        return values[0];
    }
    private static int integer(HttpServletRequest req,String name,int fallback) {
        String value=parameter(req,name); return value==null?fallback:Integer.parseInt(value);
    }
    private static Boolean bool(HttpServletRequest req,String name) {
        String value=parameter(req,name);
        if (value==null) return null;
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean");
        return Boolean.valueOf(value);
    }
    private static LocalDate date(HttpServletRequest req,String name) {
        String value=parameter(req,name);
        try { return value==null?null:LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException invalid) { throw new DispatchErrors.DateRangeInvalid(); }
    }
    private static <T extends Enum<T>> T enumeration(HttpServletRequest req,String name,Class<T> type) {
        String value=parameter(req,name); return value==null?null:Enum.valueOf(type,value);
    }
    private static String body(HttpServletRequest req,String field) throws IOException {
        return bodyFields(req,field).get(field).textValue();
    }
    private static JsonNode bodyFields(HttpServletRequest req,String... fields) throws IOException {
        JsonNode body=JsonRequests.object(req);
        if (body.size()!=fields.length)
            throw new IllegalArgumentException("Invalid request fields");
        for (String field:fields) if (!body.has(field) || !body.get(field).isTextual())
            throw new IllegalArgumentException("Invalid request fields");
        return body;
    }
    private static void send(HttpServletResponse resp,int status,Object body) throws IOException {
        com.miniongvang.web.WebResponses.json(resp, status, body);
    }
    private static void error(HttpServletResponse resp,int status,String code) throws IOException {
        com.miniongvang.web.WebResponses.error(resp, status, code);
    }
}
