package com.nedama.arifa.gateway83;

import java.util.List;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.nedama.arifa.gateway83.profile.ArifaSmsExtensionPoint;
import com.nedama.arifa.gateway83.profile.ArifaSmsProfileResource;

/**
 * Entry point of the Arifa SMS Notification module for Ignition 8.3.
 *
 * <p>In 8.3 every extension point, whichever category it extends, is declared here and Ignition manages its
 * lifecycle. There is nothing to subscribe to in {@link #setup}.</p>
 */
public class ArifaGatewayHook extends AbstractGatewayModuleHook {

    /** Prefix of the texts in ArifaNotification.properties (the profile type and pipeline block texts). */
    public static final String BUNDLE = "ArifaNotification";

    /** Prefix of the texts in ArifaSmsProfileSettings.properties (the help text of each settings field). */
    public static final String SETTINGS_BUNDLE = "ArifaSmsProfileSettings";

    /**
     * The texts are registered in the constructor, not in setup(): on 8.3 the web pages can ask for a text before
     * setup() has run, and a text that is not registered then is shown as its raw key.
     */
    public ArifaGatewayHook() {
        BundleUtil.get().addBundle(BUNDLE, getClass(), BUNDLE);
        BundleUtil.get().addBundle(SETTINGS_BUNDLE, ArifaSmsProfileResource.class, SETTINGS_BUNDLE);
    }

    @Override
    public void setup(GatewayContext context) {
        // Nothing to set up: the extension point below is all Arifa needs from the gateway.
    }

    @Override
    public void startup(LicenseState licenseState) {
        // Nothing to start. Each profile starts its own threads when Ignition creates it.
    }

    @Override
    public void shutdown() {
        BundleUtil.get().removeBundle(BUNDLE);
        BundleUtil.get().removeBundle(SETTINGS_BUNDLE);
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
    public List<? extends ExtensionPoint<?>> getExtensionPoints() {
        return List.of(new ArifaSmsExtensionPoint());
    }
}
