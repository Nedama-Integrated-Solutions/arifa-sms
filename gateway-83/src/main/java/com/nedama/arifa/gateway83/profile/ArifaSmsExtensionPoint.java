package com.nedama.arifa.gateway83.profile;

import java.util.Optional;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileConfig;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileExtensionPoint;
import com.inductiveautomation.ignition.gateway.audit.AuditProfileType;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.SecretReferenceProperty;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;

/**
 * The "Arifa SMS" entry in Config > Alarming > Notification > Create new profile on Ignition 8.3.
 * Modelled on Inductive Automation's Slack alarm notification example.
 */
public class ArifaSmsExtensionPoint extends AlarmNotificationProfileExtensionPoint<ArifaSmsProfileResource> {

    /**
     * Identifies the profile type in the gateway's configuration and in backups. It is the same value the 8.1
     * module uses, so a profile can be carried over on upgrade. Never change it after the first client install.
     */
    public static final String TYPE_ID = "ArifaSms";

    public ArifaSmsExtensionPoint() {
        super(TYPE_ID,
                "ArifaNotification.ArifaSmsProfileType.Name",
                "ArifaNotification.ArifaSmsProfileType.Description",
                ArifaSmsProfileResource.class);

        // If the audit profile is renamed, update our configuration. If someone tries to delete it, prevent that.
        addReferenceProperty(
                "auditProfileName",
                builder -> builder
                        .value(ArifaSmsProfileResource::auditProfileName)
                        .targetType(AuditProfileType.RESOURCE_TYPE)
                        .onUpdate((oldResource, newName) -> oldResource.withAuditProfileName(newName))
        );

        // The API key can be a reference to a secret held by another secret provider. This keeps the reference
        // right when that provider is renamed. Needs Ignition 8.3.3 or later.
        addReferenceProperty(
                "apiKey",
                SecretReferenceProperty.<ArifaSmsProfileResource>builder()
                        .setGetSecretConfigFunction(ArifaSmsProfileResource::apiKey)
                        .setUpdateSecretConfigFunction(ArifaSmsProfileResource::withApiKey)
                        .build()
        );
    }

    @Override
    public AlarmNotificationProfile createNewProfile(
            GatewayContext gatewayContext,
            DecodedResource<ExtensionPointConfig<AlarmNotificationProfileConfig, ?>> decodedResource,
            ArifaSmsProfileResource profileResource) throws Exception {
        return new ArifaSmsProfile(gatewayContext, decodedResource, profileResource);
    }

    @Override
    protected void validate(ArifaSmsProfileResource settings, ValidationErrors.Builder errors) {
        errors.checkField(settings.timeoutSeconds() == null || settings.timeoutSeconds() >= 0,
                "timeoutSeconds", "The request timeout cannot be negative");
        errors.checkField(settings.maxSmsPerHour() == null || settings.maxSmsPerHour() >= 0,
                "maxSmsPerHour", "Maximum SMS per hour cannot be negative. Use 0 for no limit");
        errors.checkField(settings.ackValidMinutes() == null || settings.ackValidMinutes() >= 0,
                "ackValidMinutes", "The code validity cannot be negative");
        errors.checkField(settings.pollSeconds() == null || settings.pollSeconds() >= 0,
                "pollSeconds", "The reply check interval cannot be negative");
        super.validate(settings, errors);
    }

    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        return Optional.of(
                new ExtensionPointResourceForm(
                        AlarmNotificationProfileConfig.RESOURCE_TYPE,
                        "Alarm Notification Profile",
                        TYPE_ID,
                        SchemaUtil.fromType(AlarmNotificationProfileConfig.class),
                        SchemaUtil.fromType(ArifaSmsProfileResource.class)
                )
        );
    }
}
