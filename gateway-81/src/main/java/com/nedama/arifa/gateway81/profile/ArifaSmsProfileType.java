package com.nedama.arifa.gateway81.profile;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileType;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistentRecord;
import com.inductiveautomation.ignition.gateway.localdb.persistence.RecordMeta;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * The "Arifa SMS" entry in Config > Alarming > Notification > Create new profile.
 */
public class ArifaSmsProfileType extends AlarmNotificationProfileType {

    /**
     * Identifies the profile type in the gateway's database and in gateway backups.
     * Never change it after the first client install, or existing profiles stop loading.
     */
    public static final String TYPE_ID = "ArifaSms";

    public ArifaSmsProfileType() {
        super(TYPE_ID,
                "ArifaNotification." + "ArifaSmsProfileType.Name",
                "ArifaNotification." + "ArifaSmsProfileType.Description");
    }

    @Override
    public RecordMeta<? extends PersistentRecord> getSettingsRecordType() {
        return ArifaSmsProfileSettings.META;
    }

    @Override
    public AlarmNotificationProfile createNewProfile(GatewayContext context,
                                                     AlarmNotificationProfileRecord profileRecord) throws Exception {
        ArifaSmsProfileSettings settings = findProfileSettingsRecord(context, profileRecord);

        if (settings == null) {
            throw new Exception(
                    String.format("Couldn't find settings record for profile '%s'.", profileRecord.getName()));
        }

        return new ArifaSmsProfile(context, profileRecord, settings);
    }
}
