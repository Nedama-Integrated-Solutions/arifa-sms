package com.nedama.arifa.gateway81;

import com.inductiveautomation.ignition.alarming.AlarmNotificationContext;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.services.ModuleServiceConsumer;
import com.nedama.arifa.gateway81.profile.ArifaSmsProfileSettings;
import com.nedama.arifa.gateway81.profile.ArifaSmsProfileType;
import org.apache.log4j.Logger;

/**
 * Entry point of the Arifa module on Ignition 8.1.
 *
 * <p>Follows Inductive Automation's Slack alarm notification example: wait for the alarm
 * notification service to be ready, then add our profile type to it. Arifa uses Ignition's
 * built-in SMS contact type, so there is no contact type to register.</p>
 */
public class ArifaGatewayHook extends AbstractGatewayModuleHook implements ModuleServiceConsumer {

    /** Never change this after the first client install. It must match build-81/pom.xml. */
    public static final String MODULE_ID = "com.nedama.ignition.arifa";

    /** Prefix of the localised texts in ArifaNotification.properties (the profile type and pipeline block texts). */
    public static final String BUNDLE = "ArifaNotification";

    /**
     * Prefix of the localised texts in ArifaSmsProfileSettings.properties (every label and help text on the profile
     * settings form). It must equal the record's name, because the form asks for keys such as
     * "ArifaSmsProfileSettings.Username.Name".
     */
    public static final String SETTINGS_BUNDLE = "ArifaSmsProfileSettings";

    private final LoggerEx log = new LoggerEx(Logger.getLogger(getClass()));

    private volatile GatewayContext gatewayContext;
    private volatile AlarmNotificationContext notificationContext;

    /**
     * The texts are registered here, in the constructor, and not in setup(). The gateway's web pages can ask for a
     * label before setup() has run, and a label with no registered text is shown as its raw key between question
     * marks. Both files are registered explicitly: relying on Ignition to find the settings file by itself left every
     * label on the profile form showing as a raw key on Ignition 8.1.53.
     */
    public ArifaGatewayHook() {
        BundleUtil.get().addBundle(BUNDLE, getClass(), BUNDLE);
        BundleUtil.get().addBundle(SETTINGS_BUNDLE, ArifaSmsProfileSettings.class, SETTINGS_BUNDLE);
    }

    @Override
    public void setup(GatewayContext context) {
        this.gatewayContext = context;

        context.getModuleServicesManager().subscribe(AlarmNotificationContext.class, this);

        // Creates or updates the table that stores the profile settings in the gateway's internal database
        try {
            context.getSchemaUpdater().updatePersistentRecords(ArifaSmsProfileSettings.META);
        } catch (Exception e) {
            log.error("Error configuring the internal database for the Arifa SMS notification module.", e);
        }
    }

    /**
     * Arifa needs no Inductive Automation license of its own. Without this, Ignition treats the module as
     * unlicensed and stops it when the 2 hour trial runs out.
     */
    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public void startup(LicenseState licenseState) {
        // Nothing to do. The profile type is added when the alarm notification service becomes ready.
    }

    @Override
    public void shutdown() {
        gatewayContext.getModuleServicesManager().unsubscribe(AlarmNotificationContext.class, this);

        if (notificationContext != null) {
            try {
                notificationContext.getAlarmNotificationManager()
                        .removeAlarmNotificationProfileType(new ArifaSmsProfileType());
            } catch (Exception e) {
                log.error("Error removing the Arifa SMS notification profile type.", e);
            }
        }
        BundleUtil.get().removeBundle(BUNDLE);
        BundleUtil.get().removeBundle(SETTINGS_BUNDLE);
    }

    @Override
    public void serviceReady(Class<?> serviceClass) {
        if (serviceClass == AlarmNotificationContext.class) {
            notificationContext = gatewayContext.getModuleServicesManager()
                    .getService(AlarmNotificationContext.class);
            try {
                notificationContext.getAlarmNotificationManager()
                        .addAlarmNotificationProfileType(new ArifaSmsProfileType());
            } catch (Exception e) {
                log.error("Error adding the Arifa SMS notification profile type.", e);
            }
        }
    }

    @Override
    public void serviceShutdown(Class<?> serviceClass) {
        if (serviceClass == AlarmNotificationContext.class) {
            notificationContext = null;
        }
    }
}
