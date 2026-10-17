package com.miniongvang.integration;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** VNPay PAY 2.1.0 transport and signature rules; never trusts browser return as authority. */
public final class VnpayGateway {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(VIETNAM);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String tmnCode, secret, returnUrl;
    private final URI paymentEndpoint, queryEndpoint;
    private final HttpClient http;

    public VnpayGateway(String tmnCode, String secret, String returnUrl, URI paymentEndpoint, URI queryEndpoint,
                        HttpClient http) {
        if (tmnCode == null || !tmnCode.matches("[A-Za-z0-9]{8}") || secret == null || secret.isBlank()
                || returnUrl == null || !returnUrl.startsWith("https://")
                || paymentEndpoint == null || queryEndpoint == null || http == null)
            throw new IllegalArgumentException("Invalid VNPay configuration");
        this.tmnCode=tmnCode; this.secret=secret; this.returnUrl=returnUrl;
        this.paymentEndpoint=paymentEndpoint; this.queryEndpoint=queryEndpoint; this.http=http;
    }

    public static VnpayGateway fromEnvironment() {
        String code=System.getenv("VNPAY_TMN_CODE"), secret=System.getenv("VNPAY_HASH_SECRET");
        String callback=System.getenv("VNPAY_RETURN_URL");
        if (code==null || code.isBlank() || secret==null || secret.isBlank()
                || callback==null || callback.isBlank()) throw new IllegalStateException("VNPAY_CONFIG_MISSING");
        return new VnpayGateway(code,secret,callback,
                URI.create("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"),
                URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction"),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public String paymentUrl(String reference, String orderId, BigDecimal amount, Instant created, String clientIp) {
        return paymentUrlWithInfo(reference, orderInfo(orderId), amount, created, clientIp);
    }



    private String paymentUrlWithInfo(String reference, String info, BigDecimal amount, Instant created, String clientIp) {
        if (!reference.matches("[A-Za-z0-9]{1,100}") || clientIp==null || !clientIp.matches("[0-9a-fA-F:.]{3,45}"))
            throw new IllegalArgumentException("Invalid VNPay request");
        long cents=amount.movePointRight(2).longValueExact();
        if (cents<100 || cents>999_999_999_999L) throw new IllegalArgumentException("VNPay amount outside range");
        Map<String,String> params=new TreeMap<>();
        params.put("vnp_Version","2.1.0"); params.put("vnp_Command","pay"); params.put("vnp_TmnCode",tmnCode);
        params.put("vnp_Amount",Long.toString(cents)); params.put("vnp_CurrCode","VND");
        params.put("vnp_TxnRef",reference); params.put("vnp_OrderInfo",info);
        params.put("vnp_OrderType","other"); params.put("vnp_Locale","vn");
        params.put("vnp_IpAddr",clientIp); params.put("vnp_ReturnUrl",returnUrl);
        params.put("vnp_CreateDate",DATE.format(created));
        params.put("vnp_ExpireDate",DATE.format(created.plusSeconds(15*60)));
        String data=canonical(params);
        return paymentEndpoint+"?"+data+"&vnp_SecureHash="+hmac(data);
    }

    public Map<String,String> parseQuery(String raw) {
        if (raw==null || raw.length()>8192) throw new IllegalArgumentException("Invalid VNPay query");
        Map<String,String> values=new LinkedHashMap<>();
        for (String pair:raw.split("&",-1)) {
            String[] parts=pair.split("=",2);
            if (parts.length!=2) throw new IllegalArgumentException("Invalid VNPay parameter");
            String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8);
            String value=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);
            if (!key.startsWith("vnp_") || values.putIfAbsent(key,value)!=null)
                throw new IllegalArgumentException("Duplicate or unexpected VNPay parameter");
        }
        return Map.copyOf(values);
    }

    public static String orderInfo(String orderId) {
        return "Thanh toan don hang "+orderId.replace("-","");
    }

    public boolean verifyCallback(Map<String,String> input) {
        String supplied=input.get("vnp_SecureHash");
        if (supplied==null || !supplied.matches("[0-9a-fA-F]{128}")) return false;
        Map<String,String> signed=new TreeMap<>(input);
        signed.remove("vnp_SecureHash"); signed.remove("vnp_SecureHashType");
        if (!tmnCode.equals(input.get("vnp_TmnCode"))) return false;
        return MessageDigest.isEqual(hmac(canonical(signed)).getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public Map<String,String> query(String reference, Instant created, String ip) {
        Map<String,String> request=new LinkedHashMap<>();
        request.put("vnp_RequestId",java.util.UUID.randomUUID().toString().replace("-",""));
        request.put("vnp_Version","2.1.0"); request.put("vnp_Command","querydr");
        request.put("vnp_TmnCode",tmnCode); request.put("vnp_TxnRef",reference);
        request.put("vnp_TransactionDate",DATE.format(created));
        request.put("vnp_CreateDate",DATE.format(Instant.now())); request.put("vnp_IpAddr",ip);
        request.put("vnp_OrderInfo","Query "+reference);
        request.put("vnp_SecureHash",hmac(String.join("|",request.values())));
        try {
            HttpRequest httpRequest=HttpRequest.newBuilder(queryEndpoint).timeout(Duration.ofSeconds(8))
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))).build();
            HttpResponse<String> response=http.send(httpRequest,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200 || response.body().length()>16384) throw new IllegalStateException("VNPAY_QUERY_UNAVAILABLE");
            JsonNode json=JSON.readTree(response.body());
            if (json==null || !json.isObject()) throw new IllegalStateException("VNPAY_QUERY_INVALID");
            Map<String,String> result=new LinkedHashMap<>();
            for (String key:json.propertyNames()) if (key.startsWith("vnp_") && json.get(key).isValueNode())
                result.put(key,json.get(key).asText());
            return Map.copyOf(result);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("VNPAY_QUERY_UNAVAILABLE",failure);
        }
    }

    public boolean verifyQuery(Map<String,String> response) {
        String supplied=response.get("vnp_SecureHash");
        if (supplied==null || !supplied.matches("[0-9a-fA-F]{128}") || !tmnCode.equals(response.get("vnp_TmnCode"))) return false;
        String[] names={"vnp_ResponseId","vnp_Command","vnp_ResponseCode","vnp_Message","vnp_TmnCode",
                "vnp_TxnRef","vnp_Amount","vnp_BankCode","vnp_PayDate","vnp_TransactionNo",
                "vnp_TransactionType","vnp_TransactionStatus","vnp_OrderInfo"};
        StringBuilder data=new StringBuilder();
        for (String name:names) { if (data.length()>0) data.append('|'); data.append(response.getOrDefault(name,"")); }
        if (response.containsKey("vnp_PromotionCode") || response.containsKey("vnp_PromotionAmount")) {
            data.append('|').append(response.getOrDefault("vnp_PromotionCode",""));
            data.append('|').append(response.getOrDefault("vnp_PromotionAmount",""));
        }
        return MessageDigest.isEqual(hmac(data.toString()).getBytes(StandardCharsets.US_ASCII),
                supplied.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private String hmac(String data) {
        try {
            Mac mac=Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("VNPay HMAC unavailable",failure); }
    }

    private static String canonical(Map<String,String> params) {
        StringBuilder result=new StringBuilder();
        for (var entry:new TreeMap<>(params).entrySet()) {
            if (!result.isEmpty()) result.append('&');
            result.append(URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8));
            result.append('='); result.append(URLEncoder.encode(entry.getValue(),StandardCharsets.UTF_8));
        }
        return result.toString();
    }
}
