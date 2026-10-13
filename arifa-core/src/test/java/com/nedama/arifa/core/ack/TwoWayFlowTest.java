package com.nedama.arifa.core.ack;

import com.nedama.arifa.core.africastalking.AfricasTalkingConfig;
import com.nedama.arifa.core.africastalking.AfricasTalkingEnvironment;
import com.nedama.arifa.core.africastalking.AfricasTalkingTransport;
import com.nedama.arifa.core.ack.InboxPoller.PollResult;
import com.nedama.arifa.core.notify.NotificationOutcome;
import com.nedama.arifa.core.notify.SmsNotifier;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole two-way path, with the real classes and a fake Africa's Talking server:
 * send an alarm SMS carrying a code, receive the operator's reply in the exact format the
 * sandbox returned, and acknowledge. Only the Ignition alarm manager is replaced by a list.
 */
class TwoWayFlowTest {

    private static final String PHONE = "+254700000123";
    private static final String SHORTCODE = "12345";

    private HttpServer server;
    private final List<String> sentForms = new CopyOnWriteArrayList<>();
    private volatile String inboxJson = "{\"SMSMessageData\":{\"Messages\":[]}}";

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/version1/messaging", exchange -> {
            String body;
            if ("POST".equals(exchange.getRequestMethod())) {
                sentForms.add(URLDecoder.decode(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));
                body = "{\"SMSMessageData\":{\"Message\":\"Sent to 1/1 Total Cost: KES 0.8000\",\"Recipients\":["
                        + "{\"cost\":\"KES 0.8000\",\"messageId\":\"ATXid_1\",\"messageParts\":1,\"number\":\""
                        + PHONE + "\",\"status\":\"Success\",\"statusCode\":101}]}}";
            } else {
                body = inboxJson;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopFakeServer() {
        server.stop(0);
    }

    private AfricasTalkingTransport transport() {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new AfricasTalkingTransport(new AfricasTalkingConfig(
                "sandbox", "test-key", AfricasTalkingEnvironment.SANDBOX, SHORTCODE, Duration.ofSeconds(5), base));
    }

    /** A reply exactly as the sandbox inbox returned it. */
    private void operatorReplies(long id, String text, String from, Instant at) {
        inboxJson = "{\"SMSMessageData\":{\"Messages\":[{\"linkId\":\"SampleLinkId\",\"text\":\"" + text
                + "\",\"to\":\"" + SHORTCODE + "\",\"id\":" + id + ",\"date\":\"" + at + "\",\"from\":\""
                + from + "\"}]}}";
    }

    private static final class Alarm {
        final List<String> acknowledgedEventIds = new ArrayList<>();
        final List<String> acknowledgedBy = new ArrayList<>();
    }

    @Test
    void alarmSmsCarriesTheCodeAndTheReplyAcknowledges() {
        AfricasTalkingTransport transport = transport();
        SmsNotifier notifier = new SmsNotifier(transport, "254", 2, 0, ms -> { });
        AckRegistry<String> registry = new AckRegistry<>(Duration.ofMinutes(60));
        Alarm alarm = new Alarm();
        InboxPoller<String> poller = new InboxPoller<>(transport, registry, new ReplyParser("NEDAMA"), claim -> {
            alarm.acknowledgedEventIds.add(claim.attachment());
            alarm.acknowledgedBy.add(claim.number());
            return new AckOutcome(true, "acknowledged");
        }, new NoLog(), "254");

        // 1. an alarm happens: register a code, send the SMS with the instruction
        String code = registry.register(List.of(PHONE), "event-42");
        NotificationOutcome sent = notifier.send(List.of("0700 000 123"), "Tank T-101 temp 85\u00b0C HIGH",
                "Reply NEDAMA " + code + " to ack");

        assertTrue(sent.success());
        String form = sentForms.get(0);
        assertTrue(form.contains("from=" + SHORTCODE));
        assertTrue(form.contains("message=Tank T-101 temp 85degC HIGH\nReply NEDAMA " + code + " to ack"));

        // 2. nothing has been answered yet
        assertEquals(0, poller.pollOnce().acknowledged());
        assertTrue(alarm.acknowledgedEventIds.isEmpty());

        // 3. the operator replies from the phone the SMS went to
        operatorReplies(4361, "NEDAMA " + code, PHONE, Instant.now());
        PollResult result = poller.pollOnce();

        assertEquals(1, result.acknowledged());
        assertEquals(List.of("event-42"), alarm.acknowledgedEventIds);
        assertEquals(List.of(PHONE), alarm.acknowledgedBy);
        assertEquals(0, registry.pendingCount());

        // 4. nothing waiting any more, so no more requests are needed
        assertEquals(PollResult.IDLE, poller.pollOnce());
    }

    @Test
    void aReplyFromSomeoneElseOrWithTheWrongCodeAcknowledgesNothing() {
        AfricasTalkingTransport transport = transport();
        AckRegistry<String> registry = new AckRegistry<>(Duration.ofMinutes(60));
        List<String> acked = new ArrayList<>();
        InboxPoller<String> poller = new InboxPoller<>(transport, registry, new ReplyParser("NEDAMA"), claim -> {
            acked.add(claim.attachment());
            return new AckOutcome(true, "ok");
        }, new NoLog(), "254");
        String code = registry.register(List.of(PHONE), "event-42");
        String wrong = code.equals("0000") ? "1111" : "0000";

        operatorReplies(10, "NEDAMA " + wrong, PHONE, Instant.now());
        PollResult wrongCode = poller.pollOnce();
        operatorReplies(11, "NEDAMA " + code, "+254700000999", Instant.now());
        PollResult otherPhone = poller.pollOnce();

        assertEquals(1, wrongCode.ignored());
        assertEquals(1, otherPhone.ignored());
        assertTrue(acked.isEmpty());
        assertEquals(1, registry.pendingCount());

        // the real operator can still acknowledge afterwards
        operatorReplies(12, "NEDAMA " + code, PHONE, Instant.now());
        assertEquals(1, poller.pollOnce().acknowledged());
        assertEquals(List.of("event-42"), acked);
    }

    @Test
    void theSameReplyCannotAcknowledgeTwice() {
        AfricasTalkingTransport transport = transport();
        AckRegistry<String> registry = new AckRegistry<>(
                java.time.Clock.systemUTC(), Duration.ofMinutes(60), 100, new java.util.Random(7));
        List<String> acked = new ArrayList<>();
        InboxPoller<String> poller = new InboxPoller<>(transport, registry, new ReplyParser("NEDAMA"), claim -> {
            acked.add(claim.attachment());
            return new AckOutcome(true, "ok");
        }, new NoLog(), "254");
        String code = registry.register(List.of(PHONE), "event-42");
        operatorReplies(20, "NEDAMA " + code, PHONE, Instant.now());
        assertEquals(1, poller.pollOnce().acknowledged());

        // a new alarm for the same phone is waiting, and the operator sends the old, used code again
        String newCode = registry.register(List.of(PHONE), "event-43");
        assertFalse(newCode.equals(code));
        operatorReplies(21, "NEDAMA " + code, PHONE, Instant.now());
        PollResult result = poller.pollOnce();

        assertEquals(1, result.ignored());
        assertEquals(List.of("event-42"), acked);
        assertEquals(1, registry.pendingCount()); // event-43 is still waiting for its own code
    }

    /** Log that discards everything. */
    private static final class NoLog implements InboxPoller.Log {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }
    }
}
