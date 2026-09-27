package com.martecyber.ares.common;

import org.springframework.stereotype.Component;

/** Thin adapter exposing {@link PlatformSettingsService} to plugins as the {@code ares-sdk}-owned
 *  {@link PlatformFacade}. */
@Component
class PlatformFacadeImpl implements PlatformFacade {

    private final PlatformSettingsService platformSettingsService;

    PlatformFacadeImpl(PlatformSettingsService platformSettingsService) {
        this.platformSettingsService = platformSettingsService;
    }

    @Override
    public String getTimezone() {
        return platformSettingsService.getTimezone();
    }
}
