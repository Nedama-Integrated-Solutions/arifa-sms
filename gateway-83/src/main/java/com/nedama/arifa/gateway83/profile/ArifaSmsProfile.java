package com.nedama.arifa.gateway83.profile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.inductiveautomation.ignition.alarming.common.notification.NotificationProfileProperty;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileConfig;
import com.inductiveautomation.ignition.alarming.notification.NotificationContext;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.WellKnownPathTypes;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.EventData;
import com.inductiveautomation.ignition.common.alarming.config.CommonAlarmProperties;
import com.inductiveautomation.ignition.common.audit.AuditRecord;
import com.inductiveautomation.ignition.common.config.FallbackPropertyResolver;
import com.inductiveautomation.ignition.common.expressions.parsing.Parser;
import com.inductiveautomation.ignition.common.expressions.parsing.StringParser;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataQuality;
import com.inductiveautomation.ignition.common.user.ContactInfo;
import com.inductiveautomation.ignition.common.user.ContactType;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.audit.AuditProfile;
import com.inductiveautomation.ignition.gateway.audit.AuditRecordBuilder;
import com.inductiveautomation.ignition.gateway.expressions.AlarmEventCollectionExpressionParseContext;
import com.inductiveautomation.ignition.gateway.expressions.FormattedExpressionParseContext;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.nedama.arifa.core.africastalking.AfricasTalkingConfig;
import com.nedama.arifa.core.africastalking.AfricasTalkingEnvironment;
import com.nedama.arifa.core.ack.AckOutcome;
import com.nedama.arifa.core.ack.AckReport;
import com.nedama.arifa.core.ack.AckRegistry;
import com.nedama.arifa.core.ack.InboxPoller;
import com.nedama.arifa.core.ack.ReplyParser;
import com.nedama.arifa.core.africastalking.AfricasTalkingTransport;
import com.nedama.arifa.core.notify.CostSummary;
import com.nedama.arifa.core.notify.NotificationOutcome;
import com.nedama.arifa.core.notify.SendLimiter;
import com.nedama.arifa.core.notify.SmsNotifier;
import com.nedama.arifa.core.text.GsmText;
import com.nedama.arifa.core.text.PhoneNumbers;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;

/**
 * Sends alarm notifications as SMS through Africa's Talking.
 *
 * <p>This class only talks to Ignition. Everything about numbers, message text, retries and
 * deciding success lives in arifa-core's {@link SmsNotifier}, where it is unit tested.</p>
 *
 * <p>Contract with Ignition (the same on 8.1 and 8.3): every call to {@link #sendNotification} must end in exactly one of
 * {@code notificationDone()} or {@code notificationFailed(...)}. Failing is what makes the alarm
 * pipeline use its fallback, so an unexpected error must never leave a notification unanswered.</p>
 */
public class ArifaSmsProfile implements AlarmNotificationProfile {

    private static final int WORKER_THREADS = 4;
    private static final int DEFAULT_SMS_PER_HOUR = 100;
    private static final String DEFAULT_CURRENCY = "KES";
    private static final int LOG_EVERY_N_LIMITED = 25;
    private static final int DEFAULT_ACK_VALID_MINUTES = 60;
    private static final int DEFAULT_POLL_SECONDS = 10;
    private static final int MIN_POLL_SECONDS = 3;
    private static final String DEFAULT_KEYWORD = "NEDAMA";
    private static final String ACK_NOTE = "Acknowledged by SMS reply (Arifa)";

    private final GatewayContext context;
    private final String profileName;
    private final ExecutorService executor;
    private final LoggerEx log = LoggerEx.newBuilder().build(getClass());

    private volatile ProfileStatus profileStatus = ProfileStatus.UNKNOWN;
    private volatile Map<String, String> contextMap;
    private String apiKeyStatus = "not read yet";

    private String auditProfileName;
    private String defaultCountryCode = PhoneNumbers.KENYA;
    private String environmentLabel = "sandbox";

    // Cost control
    private String expectedCurrency = DEFAULT_CURRENCY;
    private int smsPerHourLimit = DEFAULT_SMS_PER_HOUR;
    private final AtomicInteger limitedInARow = new AtomicInteger();

    /** Null when the profile settings are not usable; {@link #configProblem} then says why. */
    private SmsNotifier notifier;
    private String configProblem;

    // Two-way acknowledgement. Only set up when the setting is on and the profile is usable.
    private boolean twoWay;
    private boolean sendConfirmation;
    private String keyword = "";
    private long pollSeconds = DEFAULT_POLL_SECONDS;
    private AckRegistry<AckTarget> ackRegistry;
    private InboxPoller<AckTarget> inboxPoller;
    private ScheduledExecutorService pollScheduler;

    public ArifaSmsProfile(final GatewayContext context,
                           final DecodedResource<ExtensionPointConfig<AlarmNotificationProfileConfig, ?>> profileRecord,
                           final ArifaSmsProfileResource settings) {
        this.context = context;
        this.profileName = profileRecord.name();

        final AtomicInteger threadNumber = new AtomicInteger();
        final ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "Arifa-SMS-" + profileName + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(WORKER_THREADS, threadFactory);

        String username = settings.username();
        String apiKey = null;
        String senderId = settings.senderId();
        String countryCode = settings.defaultCountryCode();
        boolean live = settings.useLiveEnvironment();
        Integer timeoutSeconds = settings.timeoutSeconds();
        Integer smsPerHour = settings.maxSmsPerHour();
        String currencyRaw = settings.expectedCurrency();
        boolean twoWayOn = settings.twoWayEnabled();
        String keywordRaw = settings.ackKeyword();
        Integer validMinutes = settings.ackValidMinutes();
        Integer pollSecs = settings.pollSeconds();
        boolean confirm = settings.sendConfirmation();
        String problem = null;
        this.auditProfileName = settings.auditProfileName();

        // The API key is a secret. Its value is read once, here, and the plain text is released straight away.
        if (settings.apiKey() == null) {
            apiKeyStatus = "none chosen (on the profile, choose Embedded for the API Key and type the key)";
        } else {
            try (Plaintext plaintext = Secret.create(context, settings.apiKey()).getPlaintext()) {
                apiKey = plaintext.getAsString(StandardCharsets.UTF_8);
                apiKeyStatus = apiKey == null || apiKey.isBlank()
                        ? "empty (type the key in the Secret box)"
                        : "read, " + apiKey.length() + " characters";
            } catch (Exception e) {
                log.error("Could not read the Arifa SMS API key secret.", e);
                apiKeyStatus = "could not be read (" + e.getClass().getSimpleName() + ")";
                problem = "the API key could not be read";
            }
        }

        String cc = StringUtils.isBlank(countryCode) ? PhoneNumbers.KENYA : countryCode.trim();
        if (!PhoneNumbers.isValidCountryCode(cc)) {
            if (problem == null) {
                problem = "the default country code must be 1 to 3 digits, for example 254";
            }
            cc = PhoneNumbers.KENYA;
        }
        this.defaultCountryCode = cc;

        if (problem == null) {
            try {
                AfricasTalkingEnvironment environment =
                        live ? AfricasTalkingEnvironment.LIVE : AfricasTalkingEnvironment.SANDBOX;
                // The Africa's Talking sandbox always uses the username "sandbox"
                String effectiveUsername = live ? username : AfricasTalkingConfig.SANDBOX_USERNAME;
                Duration timeout = Duration.ofSeconds(
                        timeoutSeconds != null && timeoutSeconds > 0 ? timeoutSeconds : 10);
                AfricasTalkingConfig config = new AfricasTalkingConfig(
                        effectiveUsername, apiKey, environment, senderId, timeout, null);
                AfricasTalkingTransport transport = new AfricasTalkingTransport(config);
                // Blank means the default cap, 0 means no cap, and a negative number is treated as the default.
                this.smsPerHourLimit = smsPerHour == null || smsPerHour < 0 ? DEFAULT_SMS_PER_HOUR : smsPerHour;
                this.expectedCurrency = StringUtils.isBlank(currencyRaw)
                        ? DEFAULT_CURRENCY
                        : currencyRaw.trim().toUpperCase(java.util.Locale.ROOT);
                SendLimiter limiter = smsPerHourLimit > 0 ? SendLimiter.perHour(smsPerHourLimit) : null;
                this.notifier = new SmsNotifier(transport, cc, SmsNotifier.DEFAULT_MAX_SEGMENTS,
                        SmsNotifier.DEFAULT_RETRY_DELAY_MILLIS, Thread::sleep, limiter);
                this.environmentLabel = live ? "live" : "sandbox";

                if (twoWayOn) {
                    String cleanKeyword = ReplyParser.normalizeKeyword(keywordRaw);
                    this.keyword = cleanKeyword.isEmpty() && StringUtils.isBlank(keywordRaw)
                            ? DEFAULT_KEYWORD
                            : cleanKeyword;
                    int minutes = validMinutes != null && validMinutes > 0 ? validMinutes : DEFAULT_ACK_VALID_MINUTES;
                    this.pollSeconds = Math.max(MIN_POLL_SECONDS,
                            pollSecs != null && pollSecs > 0 ? pollSecs : DEFAULT_POLL_SECONDS);
                    this.sendConfirmation = confirm;
                    this.ackRegistry = new AckRegistry<>(Duration.ofMinutes(minutes));
                    this.inboxPoller = new InboxPoller<>(
                            transport, ackRegistry, new ReplyParser(this.keyword),
                            this::acknowledge, new PollLog(), cc);
                    this.twoWay = true;
                }
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            }
        }
        this.configProblem = problem;
    }

    @Override
    public String getName() {
        return profileName;
    }

    @Override
    public Collection<NotificationProfileProperty<?>> getProperties() {
        return List.of(
                ArifaProperties.MESSAGE,
                ArifaProperties.THROTTLED_MESSAGE,
                ArifaProperties.TEST_MODE
        );
    }

    @Override
    public String getProfileType() {
        return ArifaSmsExtensionPoint.TYPE_ID;
    }

    @Override
    public void setContextMap(Map<String, String> contextMap) {
        this.contextMap = contextMap;
    }

    /** Gives log lines written on our own threads the same context Ignition gives its own. */
    private void applyLogContext() {
        Map<String, String> map = contextMap;
        if (map != null) {
            MDC.setContextMap(map);
        } else {
            MDC.clear();
        }
    }

    @Override
    public ProfileStatus getStatus() {
        return profileStatus;
    }

    @Override
    public Collection<ContactType> getSupportedContactTypes() {
        // Arifa uses Ignition's built-in SMS contact type, so users' existing SMS numbers just work.
        return List.of(ContactType.SMS);
    }

    @Override
    public void onStartup() {
        if (configProblem == null) {
            profileStatus = ProfileStatus.RUNNING;
            log.infof("Arifa SMS profile '%s' started (Africa's Talking %s, default country code %s, %s, expected "
                            + "cost currency %s).",
                    profileName, environmentLabel, defaultCountryCode,
                    smsPerHourLimit > 0 ? "limit " + smsPerHourLimit + " SMS per hour" : "no SMS limit",
                    expectedCurrency);
            log.infof("Arifa SMS profile '%s': API key %s; two-way acknowledgement %s. Waiting for alarm notifications.",
                    profileName, apiKeyStatus, twoWay ? "on" : "off");
            if (twoWay) {
                startReplyPolling();
            }
        } else {
            profileStatus = new ProfileStatus(ProfileStatus.State.Errored, LocalizedString.createRaw(configProblem));
            log.errorf("Arifa SMS profile '%s' is not configured correctly: %s. "
                    + "API key: %s. Notifications will fail until it is fixed (Test Mode still works).",
                    profileName, configProblem, apiKeyStatus);
        }
    }

    @Override
    public void onShutdown() {
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
        }
        executor.shutdown();
        log.infof("Arifa SMS profile '%s' stopped.", profileName);
    }

    // ------------------------------------------------------------------
    // Two-way acknowledgement
    // ------------------------------------------------------------------

    /**
     * Checks for replies on a timer. Each check makes no network request unless a code is waiting,
     * so an idle plant costs nothing.
     */
    private void startReplyPolling() {
        final AtomicInteger threadNumber = new AtomicInteger();
        pollScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Arifa-Replies-" + profileName + "-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        pollScheduler.scheduleWithFixedDelay(this::pollForReplies, pollSeconds, pollSeconds, TimeUnit.SECONDS);
        log.infof("Arifa SMS profile '%s': two-way acknowledgement is on (keyword '%s', checking for replies "
                        + "every %d s while a code is waiting).",
                profileName, keyword.isEmpty() ? "(none)" : keyword, pollSeconds);
    }

    /** Runs on the timer. A scheduled task that throws is silently cancelled, so nothing may escape. */
    private void pollForReplies() {
        applyLogContext();
        try {
            inboxPoller.pollOnce();
        } catch (Throwable t) {
            log.error("Unexpected error while checking for Arifa SMS replies.", t);
        }
    }

    /** The text added to the end of the alarm SMS, for example "Reply NEDAMA 4821 to ack". */
    private String ackInstruction(String code) {
        return "Reply " + (keyword.isEmpty() ? "" : keyword + " ") + code + " to ack";
    }

    /**
     * Called by the poller when a valid reply has been matched. Acknowledges every alarm event of the
     * notification as the notified user, then optionally tells the operator what happened.
     */
    private AckOutcome acknowledge(AckRegistry.Claim<AckTarget> claim) {
        AckTarget target = claim.attachment();
        int total = target.eventIds().size();
        AckOutcome outcome;
        String confirmation;
        try {
            EventData ackData = new EventData();
            ackData.set(CommonAlarmProperties.AckUser, target.userPath());
            ackData.set(CommonAlarmProperties.AckNotes, ACK_NOTE);
            // Returns the events that could NOT be acknowledged, usually because they were already acknowledged.
            Set<UUID> notAcknowledged = context.getAlarmManager().acknowledgeBulk(target.eventIds(), ackData);
            int failed = notAcknowledged == null ? 0 : notAcknowledged.size();
            outcome = AckReport.outcome(total, failed, target.userLabel());
            confirmation = AckReport.confirmationText(total, failed);
        } catch (Exception e) {
            log.error("Error acknowledging alarm events from an SMS reply.", e);
            outcome = new AckOutcome(false, "error while acknowledging (" + e.getClass().getSimpleName() + ")");
            confirmation = AckReport.errorConfirmationText();
        }
        confirm(claim.number(), confirmation);
        return outcome;
    }

    /** Sends the optional confirmation SMS. Problems here never change the acknowledgement result. */
    private void confirm(String number, String text) {
        if (!sendConfirmation || notifier == null) {
            return;
        }
        try {
            NotificationOutcome sent = notifier.send(List.of(number), text);
            if (!sent.success()) {
                log.errorf("Arifa SMS confirmation to %s was not sent: %s", PhoneNumbers.mask(number), sent.summary());
            }
        } catch (RuntimeException e) {
            log.error("Unexpected error sending an Arifa SMS confirmation.", e);
        }
    }

    /** Connects the poller's messages to the gateway log. */
    private final class PollLog implements InboxPoller.Log {
        @Override
        public void info(String message) {
            log.infof("[%s] %s", profileName, message);
        }

        @Override
        public void warn(String message) {
            log.errorf("[%s] %s", profileName, message);
        }
    }

    @Override
    public void sendNotification(final NotificationContext notificationContext) {
        applyLogContext();
        log.infof("Arifa SMS profile '%s' was asked to notify %s about %d alarm event(s).",
                profileName, describeUser(notificationContext), countEvents(notificationContext));
        // Ignition requires this method to return quickly, so the work happens on a worker thread.
        try {
            executor.execute(() -> deliver(notificationContext));
        } catch (RejectedExecutionException e) {
            notificationContext.notificationFailed(
                    LocalizedString.createRaw("The Arifa SMS profile is shutting down, so the SMS was not sent"));
        }
    }

    // ------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------

    /** The notified user as text for the log. Logging must never be able to stop a notification. */
    private static String describeUser(NotificationContext notificationContext) {
        try {
            return notificationContext.getUser().getPath().toString();
        } catch (RuntimeException e) {
            return "(unknown user)";
        }
    }

    /** How many alarm events this notification covers, for the log. */
    private static int countEvents(NotificationContext notificationContext) {
        try {
            int count = 0;
            for (AlarmEvent ignored : notificationContext.getAlarmEvents()) {
                count++;
            }
            return count;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void deliver(NotificationContext notificationContext) {
        applyLogContext();
        Reply reply = new Reply(notificationContext);
        try {
            String userPath = notificationContext.getUser().getPath().toString();
            List<String> numbers = smsNumbers(notificationContext);
            log.infof("Arifa SMS profile '%s': found %d SMS contact value(s) for %s.", profileName, numbers.size(), userPath);
            String message = evaluateMessageExpression(notificationContext);

            boolean testMode = notificationContext.getOrDefault(ArifaProperties.TEST_MODE);
            if (testMode) {
                logTestMode(userPath, numbers, message);
                reply.done();
                return;
            }

            if (notifier == null) {
                String text = "SMS not sent: the Arifa profile is not configured correctly (" + configProblem + ")";
                log.errorf("%s [profile '%s', user %s]", text, profileName, userPath);
                audit(false, notificationContext);
                reply.failed(text);
                return;
            }

            // Two-way: reserve a code first, because the SMS must contain it. If the send fails the code is cancelled.
            String ackCode = null;
            String instruction = null;
            if (twoWay) {
                ackCode = registerAckCode(notificationContext, userPath, numbers);
                if (ackCode != null) {
                    instruction = ackInstruction(ackCode);
                }
            }

            NotificationOutcome outcome = notifier.send(numbers, message, instruction);
            if (ackCode != null && !outcome.success()) {
                ackRegistry.cancel(ackCode);
            }
            updateStatus(outcome);

            if (outcome.success()) {
                log.infof("Arifa SMS for %s via '%s': %s", userPath, profileName, outcome.summary());
                limitedInARow.set(0);
                checkCost(outcome, userPath);
            } else if (outcome.status() == NotificationOutcome.Status.LIMITED) {
                // During an alarm flood this would repeat for every alarm, so log the first and then every 25th.
                int count = limitedInARow.incrementAndGet();
                if (count == 1 || count % LOG_EVERY_N_LIMITED == 0) {
                    log.errorf("Arifa SMS for %s via '%s' NOT SENT (%d refused so far in this run): %s",
                            userPath, profileName, count, outcome.summary());
                }
            } else {
                limitedInARow.set(0);
                log.errorf("Arifa SMS for %s via '%s' FAILED: %s", userPath, profileName, outcome.summary());
            }
            audit(outcome.success(), notificationContext);

            if (outcome.success()) {
                reply.done();
            } else {
                reply.failed(outcome.summary());
            }
        } catch (Throwable t) {
            log.error("Unexpected error sending an Arifa SMS notification.", t);
            reply.failed("Unexpected error while sending the SMS: " + t);
        }
    }

    /**
     * Registers an acknowledgement code for the numbers that can reply. Returns null, and logs why, if no code
     * could be made. The alarm SMS is then still sent, just without the reply instruction.
     */
    private String registerAckCode(NotificationContext notificationContext, String userPath, List<String> rawNumbers) {
        List<String> e164 = new ArrayList<>();
        for (String raw : rawNumbers) {
            PhoneNumbers.tryToE164(raw, defaultCountryCode).ifPresent(e164::add);
        }
        if (e164.isEmpty()) {
            return null; // nothing will be sent, so no reply is possible
        }
        try {
            List<UUID> eventIds = new ArrayList<>();
            for (AlarmEvent event : notificationContext.getAlarmEvents()) {
                eventIds.add(event.getId());
            }
            QualifiedPath userQualifiedPath = notificationContext.getUser().getPath();
            return ackRegistry.register(e164, new AckTarget(userQualifiedPath, userPath, eventIds));
        } catch (RuntimeException e) {
            log.errorf("Arifa could not prepare an acknowledgement code for %s, so the SMS is sent without the "
                    + "reply instruction: %s", userPath, e);
            return null;
        }
    }

    /**
     * Shows the result of the last real send on the gateway's notification profile page, so a problem is
     * visible without reading logs. Nothing changes when no send was attempted (for example the user had no
     * usable number), because that is a problem with the user, not with the profile.
     */
    private void updateStatus(NotificationOutcome outcome) {
        if (outcome.status() == NotificationOutcome.Status.LIMITED) {
            profileStatus = new ProfileStatus(ProfileStatus.State.Errored,
                    LocalizedString.createRaw("SMS limit reached: " + outcome.summary()));
            return;
        }
        if (outcome.attempts() == 0) {
            return;
        }
        if (outcome.success()) {
            profileStatus = ProfileStatus.RUNNING;
        } else {
            profileStatus = new ProfileStatus(ProfileStatus.State.Errored,
                    LocalizedString.createRaw("Last send failed: " + outcome.summary()));
        }
    }

    /**
     * Warns when Africa's Talking bills a message in a currency other than the one expected. On Airtel Kenya that
     * can mean the message was charged as international traffic, which costs many times more.
     */
    private void checkCost(NotificationOutcome outcome, String userPath) {
        CostSummary cost = CostSummary.of(outcome.results());
        if (!cost.onlyCurrency(expectedCurrency)) {
            log.errorf("Arifa SMS for %s via '%s': UNEXPECTED COST. Billed %s but %s was expected. The message may "
                            + "have been charged as international traffic. Check the Africa's Talking SMS log and "
                            + "ask them before sending more.",
                    userPath, profileName, cost.describe(), expectedCurrency);
        }
    }

    /** The values of the user's SMS contact entries, as typed in the user source. */
    private List<String> smsNumbers(NotificationContext notificationContext) {
        List<String> numbers = new ArrayList<>();
        for (ContactInfo info : notificationContext.getUser().getContactInfo()) {
            if (ContactType.SMS.getContactType().equalsIgnoreCase(info.getContactType())) {
                numbers.add(info.getValue());
            }
        }
        return numbers;
    }

    private void logTestMode(String userPath, List<String> numbers, String message) {
        List<String> masked = new ArrayList<>();
        int invalid = 0;
        for (String raw : numbers) {
            String e164 = PhoneNumbers.tryToE164(raw, defaultCountryCode).orElse(null);
            if (e164 == null) {
                invalid++;
            } else {
                masked.add(PhoneNumbers.mask(e164));
            }
        }
        // Shows the reply instruction with #### in place of the code. No code is registered in test mode.
        String instruction = twoWay ? ackInstruction("####") : null;
        String cleaned = GsmText.truncateToSegments(message, SmsNotifier.DEFAULT_MAX_SEGMENTS, instruction);
        log.infof("THIS PROFILE IS RUNNING IN TEST MODE. The following WOULD have been sent:\n"
                        + "User: %s\nTo: %s%s\nSegments: %d\nMessage: %s",
                userPath,
                masked.isEmpty() ? "(no usable SMS number)" : String.join(", ", masked),
                invalid > 0 ? " (+" + invalid + " invalid number(s) skipped)" : "",
                GsmText.segmentCount(cleaned),
                cleaned);
    }

    /** Evaluates the message expression of the notification block, as the Slack example does. */
    private String evaluateMessageExpression(NotificationContext notificationContext) {
        Parser parser = new StringParser();

        FallbackPropertyResolver resolver =
                new FallbackPropertyResolver(context.getAlarmManager().getPropertyResolver());

        FormattedExpressionParseContext parseContext =
                new FormattedExpressionParseContext(
                        new AlarmEventCollectionExpressionParseContext(resolver, notificationContext.getAlarmEvents()));

        boolean isThrottled = notificationContext.getAlarmEvents().size() > 1;
        String expressionString = isThrottled
                ? notificationContext.getOrDefault(ArifaProperties.THROTTLED_MESSAGE)
                : notificationContext.getOrDefault(ArifaProperties.MESSAGE);

        String evaluated = expressionString;
        try {
            QualifiedValue value = parser.parse(expressionString, parseContext).execute();
            if (value.getQuality().isGood()) {
                evaluated = TypeUtilities.toString(value.getValue());
            }
        } catch (Exception e) {
            log.errorf("Error parsing expression '%s'.", expressionString, e);
        }

        log.tracef("Message evaluated to '%s'.", evaluated);
        return evaluated;
    }

    /** Writes one audit record per alarm event, if an audit profile is selected. Same approach as the Slack example. */
    private void audit(boolean success, NotificationContext notificationContext) {
        if (StringUtils.isBlank(auditProfileName)) {
            return;
        }
        try {
            AuditProfile p = context.getAuditManager().getProfile(auditProfileName);
            if (p == null) {
                return;
            }
            List<AlarmEvent> alarmEvents = notificationContext.getAlarmEvents();
            for (AlarmEvent event : alarmEvents) {
                AuditRecord r = new AuditRecordBuilder()
                        .setAction("Arifa SMS")
                        .setActionTarget(
                                event.getSource().extend(WellKnownPathTypes.Event, event.getId().toString())
                                        .toString())
                        .setActionValue(success ? "SUCCESS" : "FAILURE")
                        .setActor(notificationContext.getUser().getPath().toString())
                        .setActorHost(profileName)
                        .setOriginatingContext(ApplicationScope.GATEWAY)
                        .setOriginatingSystem("Alarming")
                        .setStatusCode(success ? DataQuality.GOOD_DATA.getIntValue() : 0)
                        .setTimestamp(new Date())
                        .build();
                p.audit(r);
            }
        } catch (Exception e) {
            log.error("Error auditing Arifa SMS event.", e);
        }
    }

    /**
     * Makes sure Ignition is answered exactly once per notification, even if several code paths
     * try to answer (for example a failure after success, caused by an unexpected error).
     */
    private static final class Reply {
        private final NotificationContext context;
        private final AtomicBoolean answered = new AtomicBoolean(false);

        Reply(NotificationContext context) {
            this.context = context;
        }

        void done() {
            if (answered.compareAndSet(false, true)) {
                context.notificationDone();
            }
        }

        void failed(String message) {
            if (answered.compareAndSet(false, true)) {
                context.notificationFailed(LocalizedString.createRaw(message));
            }
        }
    }
}
