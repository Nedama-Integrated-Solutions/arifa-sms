package com.nedama.arifa.gateway81.profile;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.gateway.audit.AuditProfileRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.BooleanField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.Category;
import com.inductiveautomation.ignition.gateway.localdb.persistence.EncodedStringField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IdentityField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.IntField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.LongField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.localdb.persistence.ReferenceField;
import com.inductiveautomation.ignition.gateway.localdb.persistence.StringField;
import com.inductiveautomation.ignition.gateway.web.components.editors.PasswordEditorSource;
import simpleorm.dataset.SFieldFlags;

/**
 * The settings form shown when an Arifa SMS profile is created or edited.
 *
 * <p>Field names are the column names in the gateway's internal database. Keep them stable:
 * the Ignition 8.3 build will migrate stored 8.1 settings by these names.</p>
 *
 * <p>Labels and help texts come from ArifaSmsProfileSettings.properties, next to this class.</p>
 */
public class ArifaSmsProfileSettings extends PersistentRecord {

    public static final RecordMeta<ArifaSmsProfileSettings> META =
            new RecordMeta<>(ArifaSmsProfileSettings.class, "ArifaSmsProfileSettings");

    public static final IdentityField Id = new IdentityField(META);
    public static final LongField ProfileId = new LongField(META, "ProfileId");
    public static final ReferenceField<AlarmNotificationProfileRecord> Profile = new ReferenceField<>(
            META,
            AlarmNotificationProfileRecord.META,
            "Profile",
            ProfileId);

    // Africa's Talking account
    public static final StringField Username = new StringField(META, "Username", SFieldFlags.SMANDATORY);
    public static final EncodedStringField ApiKey = new EncodedStringField(META, "ApiKey");
    public static final BooleanField UseLiveEnvironment = new BooleanField(META, "UseLiveEnvironment");
    public static final StringField SenderId = new StringField(META, "SenderId");

    // Delivery
    public static final StringField DefaultCountryCode = new StringField(META, "DefaultCountryCode");
    public static final IntField TimeoutSeconds = new IntField(META, "TimeoutSeconds");
    public static final IntField MaxSmsPerHour = new IntField(META, "MaxSmsPerHour");
    public static final StringField ExpectedCurrency = new StringField(META, "ExpectedCurrency");

    // Two-way acknowledgement (Phase 3). All optional: blank or unset means the default shown in the form help.
    public static final BooleanField TwoWayEnabled = new BooleanField(META, "TwoWayEnabled");
    public static final StringField AckKeyword = new StringField(META, "AckKeyword");
    public static final IntField AckValidMinutes = new IntField(META, "AckValidMinutes");
    public static final IntField PollSeconds = new IntField(META, "PollSeconds");
    public static final BooleanField SendConfirmation = new BooleanField(META, "SendConfirmation");

    // Optional auditing
    public static final LongField AuditProfileId = new LongField(META, "AuditProfileId");
    public static final ReferenceField<AuditProfileRecord> AuditProfile = new ReferenceField<>(
            META, AuditProfileRecord.META, "AuditProfile", AuditProfileId);

    static final Category AfricasTalking = new Category("ArifaSmsProfileSettings.Category.AfricasTalking", 1)
            .include(Username, ApiKey, UseLiveEnvironment, SenderId);

    static final Category Delivery = new Category("ArifaSmsProfileSettings.Category.Delivery", 2)
            .include(DefaultCountryCode, TimeoutSeconds, MaxSmsPerHour, ExpectedCurrency);

    static final Category TwoWay = new Category("ArifaSmsProfileSettings.Category.TwoWay", 3)
            .include(TwoWayEnabled, AckKeyword, AckValidMinutes, PollSeconds, SendConfirmation);

    static final Category Auditing = new Category("ArifaSmsProfileSettings.Category.Auditing", 4)
            .include(AuditProfile);

    static {
        Profile.getFormMeta().setVisible(false);
        // Masks the API key on the form. The standard editor asks for the key twice, as Ignition's
        // own SMTP settings do. The single-box editor is reported not to save reliably in 8.1.
        ApiKey.getFormMeta().setEditorSource(PasswordEditorSource.getSharedInstance());
    }

    @Override
    public RecordMeta<?> getMeta() {
        return META;
    }

    public String getAuditProfileName() {
        AuditProfileRecord rec = findReference(AuditProfile);
        return rec == null ? null : rec.getName();
    }
}
