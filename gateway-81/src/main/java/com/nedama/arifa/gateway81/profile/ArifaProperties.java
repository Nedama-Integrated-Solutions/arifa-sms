package com.nedama.arifa.gateway81.profile;

import java.util.ArrayList;
import java.util.List;

import com.inductiveautomation.ignition.alarming.common.notification.BasicNotificationProfileProperty;
import com.inductiveautomation.ignition.common.config.ConfigurationProperty;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;

import static com.inductiveautomation.ignition.common.BundleUtil.i18n;

/**
 * The properties shown on the Notification block in the alarm pipeline editor.
 * Texts come from ArifaNotification.properties.
 */
public final class ArifaProperties {

    private ArifaProperties() {
    }

    public static final BasicNotificationProfileProperty<String> MESSAGE =
            new BasicNotificationProfileProperty<>(
                    "message",
                    "ArifaNotification." + "Properties.Message.DisplayName",
                    null,
                    String.class);

    public static final BasicNotificationProfileProperty<String> THROTTLED_MESSAGE =
            new BasicNotificationProfileProperty<>(
                    "throttledMessage",
                    "ArifaNotification." + "Properties.ThrottledMessage.DisplayName",
                    null,
                    String.class);

    public static final BasicNotificationProfileProperty<Boolean> TEST_MODE =
            new BasicNotificationProfileProperty<>(
                    "testMode",
                    "ArifaNotification." + "Properties.TestMode.DisplayName",
                    null,
                    Boolean.class);

    static {
        MESSAGE.setExpressionSource(true);
        MESSAGE.setDefaultValue(i18n("ArifaNotification." + "Properties.Message.DefaultValue"));

        THROTTLED_MESSAGE.setExpressionSource(true);
        THROTTLED_MESSAGE.setDefaultValue(i18n("ArifaNotification." + "Properties.ThrottledMessage.DefaultValue"));

        TEST_MODE.setDefaultValue(false);
        List<ConfigurationProperty.Option<Boolean>> options = new ArrayList<>();
        options.add(new ConfigurationProperty.Option<>(true, new LocalizedString("words.yes")));
        options.add(new ConfigurationProperty.Option<>(false, new LocalizedString("words.no")));
        TEST_MODE.setOptions(options);
    }
}
