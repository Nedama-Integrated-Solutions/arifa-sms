package com.nedama.arifa.core.africastalking;

import com.nedama.arifa.core.json.MiniJson;
import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.Outcome;
import com.nedama.arifa.core.sms.RecipientResult;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransport;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.nedama.arifa.core.text.PhoneNumbers;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sends SMS and reads the inbox through the Africa's Talking REST API.
 *
 * <p>Two calls, both on {@code /version1/messaging}:</p>
 * <ul>
 *   <li>POST, form encoded: send a message</li>
 *   <li>GET with {@code lastReceivedId}: fetch received messages. This lets the gateway read
 *       acknowledgement replies by polling outbound, so the plant gateway never has to be
 *       reachable from the internet.</li>
 * </ul>
 *
 * <p>Uses only the JDK's {@link HttpClient}, deliberately not the AT Java SDK, which pulls in
 * old third-party HTTP libraries. Safe to share between threads.</p>
 */
public final class AfricasTalkingTransport implements SmsTransport {

    /** The same format check the official AT SDKs apply to recipients. */
    private static final Pattern AT_PHONE = Pattern.compile("^\\+\\d{1,3}\\d{3,}$");
    private static final int MAX_ERROR_BODY_CHARS = 300;

    private final AfricasTalkingConfig config;
    private final HttpClient http;

    public AfricasTalkingTransport(AfricasTalkingConfig config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(config, "config").requestTimeout())
                .build());
    }

    /** For tests, or to share one HttpClient between several transports. */
    public AfricasTalkingTransport(AfricasTalkingConfig config, HttpClient http) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String name() {
        return "Africa's Talking";
    }

    @Override
    public boolean supportsInbound() {
        return true;
    }

    public AfricasTalkingConfig config() {
        return config;
    }

    @Override
    public SendResult send(List<String> recipients, String text) throws SmsTransportException {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Message text is empty");
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(recipients));
        for (String number : unique) {
            if (number == null || !AT_PHONE.matcher(number).matches()) {
                throw new IllegalArgumentException(
                        "Recipient must be in +<country code><number> format: " + PhoneNumbers.mask(number));
            }
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", config.username());
        form.put("to", String.join(",", unique));
        form.put("message", text);
        form.put("bulkSMSMode", "1");
        if (config.senderId() != null) {
            form.put("from", config.senderId());
        }

        HttpRequest request = HttpRequest.newBuilder(config.messagingUri())
                .timeout(config.requestTimeout())
                .header("apiKey", config.apiKey())
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = execute(request, "send SMS");
        Map<String, Object> data = smsMessageData(response);
        try {
            return toSendResult(data, unique, response.statusCode());
        } catch (IllegalArgumentException e) {
            throw unexpected(response, e);
        }
    }

    private static SendResult toSendResult(Map<String, Object> data, List<String> requested, int httpStatus)
            throws SmsTransportException {
        Object rawRecipients = data.get("Recipients");
        List<Object> reported = rawRecipients == null ? List.of() : MiniJson.asArray(rawRecipients, "Recipients");
        if (reported.isEmpty()) {
            String reason = MiniJson.string(data, "Message");
            throw new SmsTransportException(
                    "Africa's Talking accepted no recipients: " + (reason == null ? "no reason given" : reason),
                    false, httpStatus, null);
        }

        Map<String, RecipientResult> byNumber = new LinkedHashMap<>();
        for (Object item : reported) {
            Map<String, Object> r = MiniJson.asObject(item, "recipient");
            Long code = MiniJson.longValue(r, "statusCode");
            int statusCode = code == null ? -1 : code.intValue();
            String rawStatus = MiniJson.string(r, "status");
            String statusName = AfricasTalkingStatus.isKnown(statusCode)
                    ? AfricasTalkingStatus.name(statusCode)
                    : (rawStatus != null ? rawStatus : AfricasTalkingStatus.name(statusCode));
            Outcome outcome = statusCode < 0 ? Outcome.PERMANENT_FAILURE : AfricasTalkingStatus.classify(statusCode);
            String number = MiniJson.string(r, "number");
            RecipientResult result = new RecipientResult(
                    number, outcome, statusCode, statusName,
                    MiniJson.string(r, "messageId"), MiniJson.string(r, "cost"));
            byNumber.put(number != null ? number : "unreported-" + byNumber.size(), result);
        }

        List<RecipientResult> results = new ArrayList<>(byNumber.values());
        for (String number : requested) {
            if (!byNumber.containsKey(number)) {
                results.add(new RecipientResult(
                        number, Outcome.PERMANENT_FAILURE, -1, "NotReturnedByProvider", null, null));
            }
        }
        return new SendResult(results, MiniJson.string(data, "Message"));
    }

    @Override
    public List<InboundSms> fetchInbound(long afterId) throws SmsTransportException {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId must be 0 or more");
        }
        URI uri = URI.create(config.messagingUri()
                + "?username=" + encode(config.username())
                + "&lastReceivedId=" + afterId);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("apiKey", config.apiKey())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = execute(request, "fetch inbox");
        Map<String, Object> data = smsMessageData(response);
        try {
            return toInbound(data, afterId);
        } catch (IllegalArgumentException e) {
            throw unexpected(response, e);
        }
    }

    private static List<InboundSms> toInbound(Map<String, Object> data, long afterId) {
        Object rawMessages = data.get("Messages");
        if (rawMessages == null) {
            return List.of();
        }
        List<InboundSms> messages = new ArrayList<>();
        for (Object item : MiniJson.asArray(rawMessages, "Messages")) {
            Map<String, Object> m = MiniJson.asObject(item, "message");
            Long id = MiniJson.longValue(m, "id");
            if (id == null || id <= afterId) {
                continue;
            }
            messages.add(new InboundSms(
                    id,
                    MiniJson.string(m, "from"),
                    MiniJson.string(m, "to"),
                    MiniJson.string(m, "text"),
                    MiniJson.string(m, "date"),
                    MiniJson.string(m, "linkId")));
        }
        messages.sort(Comparator.comparingLong(InboundSms::id));
        return List.copyOf(messages);
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    private HttpResponse<String> execute(HttpRequest request, String action) throws SmsTransportException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new SmsTransportException(
                    "Timed out trying to " + action + " with Africa's Talking", true, -1, e);
        } catch (IOException e) {
            throw new SmsTransportException(
                    "Network error trying to " + action + " with Africa's Talking: " + e.getMessage(), true, -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SmsTransportException("Interrupted while trying to " + action, false, -1, e);
        }

        int status = response.statusCode();
        if (status == 401) {
            throw new SmsTransportException(
                    "Africa's Talking rejected the credentials (HTTP 401). "
                            + "Check the username and API key, and that the key belongs to this environment.",
                    false, status, null);
        }
        if (status == 403) {
            throw new SmsTransportException(
                    "Access refused (HTTP 403). Either the credentials are wrong, or a proxy or firewall "
                            + "is blocking " + request.uri().getHost() + ".",
                    false, status, null);
        }
        if (status >= 500) {
            throw new SmsTransportException(
                    "Africa's Talking server error (HTTP " + status + "): " + snippet(response.body()),
                    true, status, null);
        }
        if (status < 200 || status >= 300) {
            throw new SmsTransportException(
                    "Africa's Talking refused the request (HTTP " + status + "): " + snippet(response.body()),
                    false, status, null);
        }
        return response;
    }

    private static Map<String, Object> smsMessageData(HttpResponse<String> response) throws SmsTransportException {
        Object data;
        try {
            data = MiniJson.asObject(MiniJson.parse(response.body()), "response").get("SMSMessageData");
        } catch (IllegalArgumentException e) {
            throw unexpected(response, e);
        }
        if (data == null) {
            throw new SmsTransportException(
                    "Unexpected response from Africa's Talking (no SMSMessageData): " + snippet(response.body()),
                    false, response.statusCode(), null);
        }
        try {
            return MiniJson.asObject(data, "SMSMessageData");
        } catch (IllegalArgumentException e) {
            throw unexpected(response, e);
        }
    }

    private static SmsTransportException unexpected(HttpResponse<String> response, IllegalArgumentException cause) {
        return new SmsTransportException(
                "Unexpected response from Africa's Talking (" + cause.getMessage() + "): " + snippet(response.body()),
                false, response.statusCode(), cause);
    }

    private static String formEncode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "(empty body)";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_BODY_CHARS ? flat : flat.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
