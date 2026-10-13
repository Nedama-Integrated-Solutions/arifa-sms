package com.nedama.arifa.core.africastalking;

import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.Outcome;
import com.nedama.arifa.core.sms.RecipientResult;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real transport against a tiny local HTTP server that imitates Africa's Talking.
 * No internet and no AT account needed.
 */
class AfricasTalkingTransportTest {

    private static final String KEY = "test-key-123";
    private static final String A = "+254711000001";
    private static final String B = "+254711000002";

    private HttpServer server;
    private boolean serverStopped;
    private final AtomicReference<Captured> lastRequest = new AtomicReference<>();
    private volatile int replyStatus = 201;
    private volatile String replyBody = "";

    /** What the fake server received. */
    record Captured(String method, String path, String query,
                    String apiKey, String accept, String contentType, String body) {
    }

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/version1/messaging", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest.set(new Captured(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("apiKey"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    body));
            byte[] out = replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeServer() {
        if (!serverStopped) {
            server.stop(0);
        }
    }

    private AfricasTalkingTransport transport(String senderId) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        AfricasTalkingConfig config = new AfricasTalkingConfig(
                "sandbox", KEY, AfricasTalkingEnvironment.SANDBOX, senderId, Duration.ofSeconds(5), base);
        return new AfricasTalkingTransport(config);
    }

    private void reply(int status, String body) {
        replyStatus = status;
        replyBody = body;
    }

    private static String recipient(String number, int code, String status) {
        return "{\"cost\":\"KES 0.8000\",\"messageId\":\"ATXid_" + number.substring(9)
                + "\",\"messageParts\":1,\"number\":\"" + number
                + "\",\"status\":\"" + status + "\",\"statusCode\":" + code + "}";
    }

    private static String sendReply(String summary, String... recipients) {
        return "{\"SMSMessageData\":{\"Message\":\"" + summary + "\",\"Recipients\":["
                + String.join(",", recipients) + "]}}";
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            fields.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return fields;
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    @Test
    void sendsTheRequestAfricasTalkingExpects() throws Exception {
        reply(201, sendReply("Sent to 2/2 Total Cost: KES 1.6000",
                recipient(A, 101, "Success"), recipient(B, 102, "Success")));

        SendResult result = transport(null).send(List.of(A, B), "Pump P-101 tripped");

        assertTrue(result.allAccepted());
        assertEquals(2, result.recipients().size());
        assertEquals("Sent to 2/2 Total Cost: KES 1.6000", result.providerSummary());

        Captured req = lastRequest.get();
        assertEquals("POST", req.method());
        assertEquals("/version1/messaging", req.path());
        assertEquals(KEY, req.apiKey());
        assertEquals("application/json", req.accept());
        assertEquals("application/x-www-form-urlencoded", req.contentType());

        Map<String, String> fields = form(req.body());
        assertEquals("sandbox", fields.get("username"));
        assertEquals(A + "," + B, fields.get("to"));
        assertEquals("Pump P-101 tripped", fields.get("message"));
        assertEquals("1", fields.get("bulkSMSMode"));
        assertFalse(fields.containsKey("from"));
    }

    @Test
    void includesSenderIdWhenConfigured() throws Exception {
        reply(201, sendReply("Sent to 1/1", recipient(A, 101, "Success")));
        transport("NEDAMA").send(List.of(A), "Test");
        assertEquals("NEDAMA", form(lastRequest.get().body()).get("from"));
    }

    @Test
    void removesDuplicateRecipients() throws Exception {
        reply(201, sendReply("Sent to 1/1", recipient(A, 101, "Success")));
        SendResult result = transport(null).send(List.of(A, A), "Test");
        assertEquals(A, form(lastRequest.get().body()).get("to"));
        assertEquals(1, result.recipients().size());
    }

    @Test
    void optedOutRecipientIsAPermanentFailure() throws Exception {
        reply(201, sendReply("Sent to 1/2",
                recipient(A, 101, "Success"), recipient(B, 406, "UserInBlacklist")));

        SendResult result = transport(null).send(List.of(A, B), "Test");

        assertFalse(result.allAccepted());
        assertTrue(result.anyAccepted());
        assertFalse(result.hasRetryableFailure());
        RecipientResult failed = result.failures().get(0);
        assertEquals(B, failed.number());
        assertEquals(Outcome.PERMANENT_FAILURE, failed.outcome());
        assertEquals(406, failed.providerStatusCode());
        assertEquals("UserInBlacklist", failed.providerStatus());
    }

    @Test
    void providerFaultIsRetryable() throws Exception {
        reply(201, sendReply("Sent to 0/1", recipient(A, 500, "InternalServerError")));
        SendResult result = transport(null).send(List.of(A), "Test");
        assertTrue(result.hasRetryableFailure());
    }

    @Test
    void recipientMissingFromResponseIsMarkedFailed() throws Exception {
        reply(201, sendReply("Sent to 1/2", recipient(A, 101, "Success")));

        SendResult result = transport(null).send(List.of(A, B), "Test");

        assertEquals(2, result.recipients().size());
        RecipientResult missing = result.failures().get(0);
        assertEquals(B, missing.number());
        assertEquals("NotReturnedByProvider", missing.providerStatus());
    }

    @Test
    void noAcceptedRecipientsThrowsWithTheReason() {
        reply(201, "{\"SMSMessageData\":{\"Message\":\"InvalidSenderId\",\"Recipients\":[]}}");

        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport("WRONGID").send(List.of(A), "Test"));

        assertFalse(e.isRetryable());
        assertTrue(e.getMessage().contains("InvalidSenderId"));
    }

    @Test
    void badCredentialsAreNotRetryable() {
        reply(401, "The supplied authentication is invalid");

        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport(null).send(List.of(A), "Test"));

        assertFalse(e.isRetryable());
        assertEquals(401, e.getHttpStatus());
        assertFalse(e.getMessage().contains(KEY));
    }

    @Test
    void serverErrorIsRetryable() {
        reply(503, "Service Unavailable");

        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport(null).send(List.of(A), "Test"));

        assertTrue(e.isRetryable());
        assertEquals(503, e.getHttpStatus());
    }

    @Test
    void otherClientErrorsAreNotRetryable() {
        reply(400, "Bad request");
        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport(null).send(List.of(A), "Test"));
        assertFalse(e.isRetryable());
    }

    @Test
    void nonJsonSuccessIsReportedNotCrashed() {
        reply(201, "<html>proxy login page</html>");
        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport(null).send(List.of(A), "Test"));
        assertFalse(e.isRetryable());
        assertTrue(e.getMessage().contains("Unexpected response"));
    }

    @Test
    void wrongJsonShapeIsReportedNotCrashed() {
        reply(201, "{\"SMSMessageData\":{\"Recipients\":\"oops\"}}");
        assertThrows(SmsTransportException.class, () -> transport(null).send(List.of(A), "Test"));
    }

    @Test
    void networkFailureIsRetryable() {
        AfricasTalkingTransport transport = transport(null);
        server.stop(0); // nothing listening any more
        serverStopped = true;

        SmsTransportException e = assertThrows(SmsTransportException.class,
                () -> transport.send(List.of(A), "Test"));

        assertTrue(e.isRetryable());
        assertEquals(-1, e.getHttpStatus());
    }

    @Test
    void rejectsBadInputBeforeCallingTheNetwork() {
        AfricasTalkingTransport transport = transport(null);
        assertThrows(IllegalArgumentException.class, () -> transport.send(List.of("0712345678"), "Test"));
        assertThrows(IllegalArgumentException.class, () -> transport.send(List.of(), "Test"));
        assertThrows(IllegalArgumentException.class, () -> transport.send(List.of(A), "  "));
        assertNull(lastRequest.get());
    }

    // ------------------------------------------------------------------
    // Inbox
    // ------------------------------------------------------------------

    @Test
    void fetchesNewInboundMessagesOldestFirst() throws Exception {
        reply(200, "{\"SMSMessageData\":{\"Messages\":["
                + "{\"linkId\":\"L2\",\"text\":\"NEDAMA 4821\",\"to\":\"28901\",\"id\":15073,"
                + "\"date\":\"2026-10-02T08:34:13.000Z\",\"from\":\"" + B + "\"},"
                + "{\"linkId\":\"L1\",\"text\":\"NEDAMA 1111\",\"to\":\"28901\",\"id\":15072,"
                + "\"date\":\"2026-10-02T08:30:00.000Z\",\"from\":\"" + A + "\"},"
                + "{\"linkId\":\"L0\",\"text\":\"old\",\"to\":\"28901\",\"id\":15000,"
                + "\"date\":\"2026-10-01T08:00:00.000Z\",\"from\":\"" + A + "\"}"
                + "]}}");

        List<InboundSms> messages = transport(null).fetchInbound(15071);

        Captured req = lastRequest.get();
        assertEquals("GET", req.method());
        assertEquals("username=sandbox&lastReceivedId=15071", req.query());
        assertEquals(KEY, req.apiKey());

        assertEquals(2, messages.size());
        assertEquals(15072, messages.get(0).id());
        assertEquals(A, messages.get(0).from());
        assertEquals("NEDAMA 1111", messages.get(0).text());
        assertEquals(15073, messages.get(1).id());
        assertEquals("28901", messages.get(1).to());
    }

    @Test
    void emptyInboxGivesEmptyList() throws Exception {
        reply(200, "{\"SMSMessageData\":{\"Messages\":[]}}");
        assertTrue(transport(null).fetchInbound(0).isEmpty());
    }

    @Test
    void inboundToStringMasksTheSender() {
        InboundSms sms = new InboundSms(1, A, "28901", "NEDAMA 4821", "now", null);
        assertFalse(sms.toString().contains(A));
    }
}
