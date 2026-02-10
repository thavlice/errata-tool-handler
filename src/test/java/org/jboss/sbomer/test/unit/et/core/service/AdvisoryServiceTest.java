package org.jboss.sbomer.test.unit.et.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.sbomer.handler.et.core.domain.advisory.Advisory;
import org.jboss.sbomer.handler.et.core.domain.advisory.Build;
import org.jboss.sbomer.handler.et.core.domain.exception.AdvisoryProcessingException;
import org.jboss.sbomer.handler.et.core.domain.generation.GenerationRequest;
import org.jboss.sbomer.handler.et.core.port.spi.ErrataTool;
import org.jboss.sbomer.handler.et.core.port.spi.FailureNotifier;
import org.jboss.sbomer.handler.et.core.port.spi.GenerationRequestService;
import org.jboss.sbomer.handler.et.core.port.spi.Koji;
import org.jboss.sbomer.handler.et.core.service.AdvisoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AdvisoryServiceTest {
    @Mock
    private ErrataTool errataTool;

    @Mock
    private GenerationRequestService generationRequestService;

    @Mock
    private Koji koji;

    @Mock
    private FailureNotifier failureNotifier;

    private AdvisoryService advisoryService;

    @BeforeEach
    void setUp() {
        advisoryService = new AdvisoryService(errataTool, generationRequestService, koji, failureNotifier);
        // Set config properties
        advisoryService.ATLAS_BUILD_PUBLISHER_NAME = "atlas-build";
        advisoryService.ATLAS_BUILD_PUBLISHER_VERSION = "1.0";
        advisoryService.ATLAS_RELEASE_PUBLISHER_NAME = "atlas-release";
        advisoryService.ATLAS_RELEASE_PUBLISHER_VERSION = "1.0";
    }

    @Test
    void shouldProcessQEAdvisoryWithRPMBuilds() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);
        List<Build> builds = List.of(
                new Build(3366231L, "cdi-api-2.0.2-15.el10", "RPM", "3366231"),
                new Build(3366232L, "httpd-2.4.51-1.el9", "RPM", "3366232"));

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.generations().size());
        assertEquals(1, result.publishers().size());
        assertEquals("atlas-build", result.publishers().get(0).name());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, times(1)).fetchBuilds(advisoryId);
        verify(generationRequestService, times(1)).requestGenerations(any(GenerationRequest.class));
        verify(koji, never()).getImageNames(anyList());
    }

    @Test
    void shouldProcessQEAdvisoryWithContainerBuilds() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);
        List<Build> builds = List.of(
                new Build(3338841L, "ubi9-container-9.1-123", "CONTAINER", null),
                new Build(3338842L, "nginx-container-1.20-456", "CONTAINER", null));

        Map<Long, String> imageDigests = new HashMap<>();
        imageDigests.put(3338841L, "registry.io/ubi9@sha256:abc123");
        imageDigests.put(3338842L, "registry.io/nginx@sha256:def456");

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);
        when(koji.getImageNames(anyList())).thenReturn(imageDigests);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.generations().size());
        assertEquals(1, result.publishers().size());
        assertEquals("atlas-build", result.publishers().get(0).name());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, times(1)).fetchBuilds(advisoryId);
        verify(koji, times(1)).getImageNames(anyList());
        verify(generationRequestService, times(1)).requestGenerations(any(GenerationRequest.class));
    }

    @Test
    void shouldProcessShippedLiveAdvisory() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "SHIPPED_LIVE", false);
        List<Build> builds = List.of(new Build(3366231L, "cdi-api-2.0.2-15.el10", "RPM", "3366231"));

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertEquals(1, result.generations().size());
        assertEquals(1, result.publishers().size());
        assertEquals("atlas-release", result.publishers().get(0).name());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, times(1)).fetchBuilds(advisoryId);
    }

    @Test
    void shouldIgnoreAdvisoryWithInvalidState() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "NEW_FILES", false);

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertTrue(result.generations().isEmpty());
        assertTrue(result.publishers().isEmpty());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, never()).fetchBuilds(advisoryId);
        verify(generationRequestService, times(1)).requestGenerations(any(GenerationRequest.class));
    }

    @Test
    void shouldHandleAdvisoryWithNoBuilds() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(Collections.emptyList());

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertTrue(result.generations().isEmpty());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, times(1)).fetchBuilds(advisoryId);
    }

    @Test
    void shouldProcessMixedBuildTypes() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);
        List<Build> builds = List.of(
                new Build(3366231L, "cdi-api-2.0.2-15.el10", "RPM", "3366231"),
                new Build(3338841L, "ubi9-container-9.1-123", "CONTAINER", null));

        Map<Long, String> imageDigests = new HashMap<>();
        imageDigests.put(3338841L, "registry.io/ubi9@sha256:abc123");

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);
        when(koji.getImageNames(anyList())).thenReturn(imageDigests);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.generations().size());

        verify(errataTool, times(1)).getInfo(advisoryId);
        verify(errataTool, times(1)).fetchBuilds(advisoryId);
        verify(koji, times(1)).getImageNames(anyList());
    }

    @Test
    void shouldSkipContainerBuildWithMissingDigest() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);
        List<Build> builds = List.of(
                new Build(3338841L, "ubi9-container-9.1-123", "CONTAINER", null),
                new Build(3338842L, "nginx-container-1.20-456", "CONTAINER", null));

        Map<Long, String> imageDigests = new HashMap<>();
        imageDigests.put(3338841L, "registry.io/ubi9@sha256:abc123");
        // Missing digest for 3338842L

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);
        when(koji.getImageNames(anyList())).thenReturn(imageDigests);

        // When
        GenerationRequest result = advisoryService.requestGenerations(advisoryId);

        // Then
        assertNotNull(result);
        assertEquals(1, result.generations().size()); // Only one build has digest

        verify(koji, times(1)).getImageNames(anyList());
    }

    @Test
    void shouldThrowExceptionWhenKojiFails() {
        // Given
        final String advisoryId = "12345";
        Advisory advisory = new Advisory(advisoryId, "QE", false);
        List<Build> builds = List.of(new Build(3338841L, "ubi9-container-9.1-123", "CONTAINER", null));

        when(errataTool.getInfo(advisoryId)).thenReturn(advisory);
        when(errataTool.fetchBuilds(advisoryId)).thenReturn(builds);
        when(koji.getImageNames(anyList())).thenThrow(new RuntimeException("Koji connection failed"));

        // When/Then
        assertThrows(AdvisoryProcessingException.class, () -> {
            advisoryService.requestGenerations(advisoryId);
        });

        verify(failureNotifier, times(1)).notify(any(), eq(null), eq(null));
    }

    @Test
    void shouldThrowExceptionWhenErrataToolFails() {
        // Given
        final String advisoryId = "12345";

        when(errataTool.getInfo(advisoryId)).thenThrow(new RuntimeException("ErrataTool connection failed"));

        // When/Then
        assertThrows(AdvisoryProcessingException.class, () -> {
            advisoryService.requestGenerations(advisoryId);
        });

        verify(failureNotifier, times(1)).notify(any(), eq(null), eq(null));
    }
}
