package com.nedama.arifa.tools;

import com.nedama.arifa.core.africastalking.AfricasTalkingConfig;
import com.nedama.arifa.core.africastalking.AfricasTalkingEnvironment;
import com.nedama.arifa.core.africastalking.AfricasTalkingTransport;
import com.nedama.arifa.core.sms.InboundSms;
import com.nedama.arifa.core.sms.RecipientResult;
import com.nedama.arifa.core.sms.SendResult;
import com.nedama.arifa.core.sms.SmsTransportException;
import com.nedama.arifa.core.text.GsmText;
import com.nedama.arifa.core.text.PhoneNumbers;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Manual end-to-end check against the real Africa's Talking SANDBOX.
 * Not a unit test: run it by hand from your IDE.
 *
 * <p>Needs two environment variables. Set them in your IDE run configuration or in a
 * git-ignored {@code .env} file (see docs/dev-setup.md), never in code:</p>
 * <ul>
 *   <li>{@code AT_API_KEY}: the API key of your AT sandbox app</li>
 *   <li>{@code AT_TEST_NUMBER}: the phone number you opened in the AT simulator, for example 0712345678</li>
 * </ul>
 *
 * <p>Optional: {@code AT_SENDER_ID} if you created a sandbox shortcode or sender ID.</p>
 *
 * <p>Success: the message appears in the simulator, and this prints ACCEPTED.</p>
 */
public final class SandboxSmoke {

    private SandboxSmoke() {
    }

    public static void main(String[] args) {
        String apiKey = requireEnv("AT_API_KEY");
        String rawNumber = requireEnv("AT_TEST_NUMBER");
        String senderId = System.getenv("AT_SENDER_ID");

        String number = PhoneNumbers.toE164(rawNumber, PhoneNumbers.KENYA);
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String message = GsmText.sanitize(
                "Arifa sandbox test " + time + ": Tank T-101 temp 85°C – HIGH. Reply NEDAMA 1234 to acknowledge.");

        AfricasTalkingConfig config = new AfricasTalkingConfig(
                AfricasTalkingConfig.SANDBOX_USERNAME, apiKey,
                AfricasTalkingEnvironment.SANDBOX,
                senderId, null, null);
        AfricasTalkingTransport transport = new AfricasTalkingTransport(config);

        System.out.println("Config:   " + config);
        System.out.println("To:       " + PhoneNumbers.mask(number));
        System.out.println("Message:  " + message);
        System.out.println("Segments: " + GsmText.segmentCount(message));
        System.out.println();

        try {
            SendResult result = transport.send(List.of(number), message);
            System.out.println("Provider: " + result.providerSummary());
            for (RecipientResult r : result.recipients()) {
                System.out.println("  " + r);
            }
            System.out.println(result.allAccepted()
                    ? "RESULT:   ACCEPTED. Check the simulator for the message."
                    : "RESULT:   NOT ACCEPTED. See the status above.");
        } catch (SmsTransportException e) {
            System.out.println("RESULT:   FAILED (" + (e.isRetryable() ? "retryable" : "permanent") + "): "
                    + e.getMessage());
            System.exit(1);
        }

        System.out.println();
        try {
            List<InboundSms> inbox = transport.fetchInbound(0);
            System.out.println("Inbox:    " + inbox.size() + " message(s) in the sandbox inbox.");
            inbox.stream().skip(Math.max(0, inbox.size() - 5)).forEach(m -> System.out.println("  " + m));
        } catch (SmsTransportException e) {
            System.out.println("Inbox:    could not fetch (" + e.getMessage() + "). "
                    + "This is expected until a sandbox shortcode exists (Phase 3).");
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Missing environment variable " + name
                    + ". Set it in your IDE run configuration or .env file (see docs/dev-setup.md).");
            System.exit(2);
        }
        return value.trim();
    }
}
