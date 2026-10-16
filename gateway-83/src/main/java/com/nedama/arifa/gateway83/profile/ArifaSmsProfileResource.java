package com.nedama.arifa.gateway83.profile;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DescriptionKey;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormReferenceType;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.IsNullable;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Minimum;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.NonSecret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * The settings of one Arifa SMS notification profile, as Ignition 8.3 stores them. The settings form is generated
 * from this record. The same settings exist in the 8.1 module as a persistent record; the names and meanings match.
 *
 * <p>Blank or missing values mean the defaults described in each field's help text, and the profile applies them.</p>
 */
public record ArifaSmsProfileResource(
        @FormCategory("AFRICA'S TALKING")
        @Label("Username")
        @FormField(FormFieldType.TEXT)
        @DescriptionKey("ArifaSmsProfileSettings.Username.Desc")
        String username,

        @FormCategory("AFRICA'S TALKING")
        @Label("API Key")
        @FormField(FormFieldType.SECRET)
        @DescriptionKey("ArifaSmsProfileSettings.ApiKey.Desc")
        SecretConfig apiKey,

        @FormCategory("AFRICA'S TALKING")
        @Label("Live")
        @FormField(FormFieldType.CHECKBOX)
        @DefaultValue("false")
        @DescriptionKey("ArifaSmsProfileSettings.UseLiveEnvironment.Desc")
        boolean useLiveEnvironment,

        @FormCategory("AFRICA'S TALKING")
        @Label("Sender ID")
        @FormField(FormFieldType.TEXT)
        @DescriptionKey("ArifaSmsProfileSettings.SenderId.Desc")
        String senderId,

        @FormCategory("DELIVERY")
        @Label("Default Country Code")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("254")
        @DescriptionKey("ArifaSmsProfileSettings.DefaultCountryCode.Desc")
        String defaultCountryCode,

        @FormCategory("DELIVERY")
        @Label("Request Timeout (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Minimum("0")
        @NonSecret
        @DefaultValue("10")
        @DescriptionKey("ArifaSmsProfileSettings.TimeoutSeconds.Desc")
        Integer timeoutSeconds,

        @FormCategory("DELIVERY")
        @Label("Maximum SMS per Hour")
        @FormField(FormFieldType.NUMBER)
        @Minimum("0")
        @NonSecret
        @DefaultValue("100")
        @DescriptionKey("ArifaSmsProfileSettings.MaxSmsPerHour.Desc")
        Integer maxSmsPerHour,

        @FormCategory("DELIVERY")
        @Label("Expected Cost Currency")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("KES")
        @DescriptionKey("ArifaSmsProfileSettings.ExpectedCurrency.Desc")
        String expectedCurrency,

        @FormCategory("TWO-WAY ACKNOWLEDGEMENT")
        @Label("Allow Acknowledgement by SMS Reply")
        @FormField(FormFieldType.CHECKBOX)
        @DefaultValue("false")
        @DescriptionKey("ArifaSmsProfileSettings.TwoWayEnabled.Desc")
        boolean twoWayEnabled,

        @FormCategory("TWO-WAY ACKNOWLEDGEMENT")
        @Label("Reply Keyword")
        @FormField(FormFieldType.TEXT)
        @NonSecret
        @DefaultValue("NEDAMA")
        @DescriptionKey("ArifaSmsProfileSettings.AckKeyword.Desc")
        String ackKeyword,

        @FormCategory("TWO-WAY ACKNOWLEDGEMENT")
        @Label("Code Valid For (minutes)")
        @FormField(FormFieldType.NUMBER)
        @Minimum("0")
        @NonSecret
        @DefaultValue("60")
        @DescriptionKey("ArifaSmsProfileSettings.AckValidMinutes.Desc")
        Integer ackValidMinutes,

        @FormCategory("TWO-WAY ACKNOWLEDGEMENT")
        @Label("Check for Replies Every (seconds)")
        @FormField(FormFieldType.NUMBER)
        @Minimum("0")
        @NonSecret
        @DefaultValue("10")
        @DescriptionKey("ArifaSmsProfileSettings.PollSeconds.Desc")
        Integer pollSeconds,

        @FormCategory("TWO-WAY ACKNOWLEDGEMENT")
        @Label("Send Confirmation SMS")
        @FormField(FormFieldType.CHECKBOX)
        @DefaultValue("false")
        @DescriptionKey("ArifaSmsProfileSettings.SendConfirmation.Desc")
        boolean sendConfirmation,

        @FormCategory("AUDITING")
        @Label("Audit Profile")
        @FormField(FormFieldType.REFERENCE)
        @FormReferenceType("ignition/audit-profile")
        @IsNullable
        @DefaultValue("null")
        @DescriptionKey("ArifaSmsProfileSettings.AuditProfile.Desc")
        String auditProfileName
) {

    /** A copy with another API key secret. Used when Ignition renames the secret provider a reference points to. */
    public ArifaSmsProfileResource withApiKey(SecretConfig newApiKey) {
        return new ArifaSmsProfileResource(username, newApiKey, useLiveEnvironment, senderId, defaultCountryCode,
                timeoutSeconds, maxSmsPerHour, expectedCurrency, twoWayEnabled, ackKeyword, ackValidMinutes,
                pollSeconds, sendConfirmation, auditProfileName);
    }

    /** A copy with another audit profile name. Used when Ignition renames the audit profile this one points to. */
    public ArifaSmsProfileResource withAuditProfileName(String newName) {
        return new ArifaSmsProfileResource(username, apiKey, useLiveEnvironment, senderId, defaultCountryCode,
                timeoutSeconds, maxSmsPerHour, expectedCurrency, twoWayEnabled, ackKeyword, ackValidMinutes,
                pollSeconds, sendConfirmation, newName);
    }
}
