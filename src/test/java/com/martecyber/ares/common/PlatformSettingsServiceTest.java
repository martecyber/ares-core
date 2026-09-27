package com.martecyber.ares.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformSettingsServiceTest {

    private PlatformSettingRepository repo;
    private PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        repo = mock(PlatformSettingRepository.class);
        when(repo.findById("timezone")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new PlatformSettingsService(repo);
    }

    @Test
    void getTimezoneFallsBackToTheConfiguredDefaultWhenNoOverrideIsStored() {
        // @Value isn't injected outside a Spring context, so the field stays at its Java default
        // (null) here — the fallback branch is still exercised, just with a null default.
        assertNull(service.getTimezone());
    }

    @Test
    void setTimezonePersistsAndIsReadBackAfterCacheInvalidation() {
        service.setTimezone("Europe/Madrid");

        var captor = org.mockito.ArgumentCaptor.forClass(PlatformSetting.class);
        verify(repo).save(captor.capture());
        assertEquals("timezone", captor.getValue().getKey());
        assertEquals("Europe/Madrid", captor.getValue().getValue());

        when(repo.findById("timezone")).thenReturn(Optional.of(captor.getValue()));
        assertEquals("Europe/Madrid", service.getTimezone());
    }

    @Test
    void rejectsABlankTimezone() {
        assertThrows(ResponseStatusException.class, () -> service.setTimezone(" "));
    }

    @Test
    void rejectsAnInvalidIanaIdentifier() {
        assertThrows(ResponseStatusException.class, () -> service.setTimezone("Not/A_Zone"));
    }
}
