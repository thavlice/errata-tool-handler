package org.jboss.sbomer.handler.et.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.sbomer.events.common.FailureSpec;
import org.jboss.sbomer.handler.et.core.domain.advisory.Advisory;
import org.jboss.sbomer.handler.et.core.domain.advisory.Build;
import org.jboss.sbomer.handler.et.core.domain.exception.AdvisoryProcessingException;
import org.jboss.sbomer.handler.et.core.domain.generation.Generation;
import org.jboss.sbomer.handler.et.core.domain.generation.GenerationRequest;
import org.jboss.sbomer.handler.et.core.domain.generation.GenerationTarget;
import org.jboss.sbomer.handler.et.core.domain.publish.Publisher;
import org.jboss.sbomer.handler.et.core.port.api.AdvisoryHandler;
import org.jboss.sbomer.handler.et.core.port.spi.ErrataTool;
import org.jboss.sbomer.handler.et.core.port.spi.FailureNotifier;
import org.jboss.sbomer.handler.et.core.port.spi.GenerationRequestService;
import org.jboss.sbomer.handler.et.core.port.spi.Koji;
import org.jboss.sbomer.handler.et.core.utility.FailureUtility;
import org.jboss.sbomer.handler.et.core.utility.TsidUtility;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Core service implementing advisory processing business logic.
 * </p>
 *
 * <p>
 * This service handles Errata Tool advisories and orchestrates the generation of SBOM requests.
 * It validates advisory states, categorizes builds by type, resolves identifiers (including
 * fetching container image digests from Koji), and publishes generation requests.
 * </p>
 *
 * <p>
 * Business Logic:
 * <ul>
 * <li>Advisory State: Only QE and SHIPPED_LIVE states are processed</li>
 * <li>Build Types: RPM builds use build ID, Container builds use image digest from Koji</li>
 * <li>Publishers: QE → Atlas Build, SHIPPED_LIVE → Atlas Release</li>
 * <li>Text-Only: Currently logged and skipped (future enhancement)</li>
 * </ul>
 * </p>
 */
@ApplicationScoped
@Slf4j
public class AdvisoryService implements AdvisoryHandler {
    ErrataTool errataTool;
    GenerationRequestService generationRequestService;
    Koji koji;
    FailureNotifier failureNotifier;

    @ConfigProperty(name = "sbomer.publisher.atlas.build.name")
    public String ATLAS_BUILD_PUBLISHER_NAME;
    @ConfigProperty(name = "sbomer.publisher.atlas.build.version")
    public String ATLAS_BUILD_PUBLISHER_VERSION;
    @ConfigProperty(name = "sbomer.publisher.atlas.release.name")
    public String ATLAS_RELEASE_PUBLISHER_NAME;
    @ConfigProperty(name = "sbomer.publisher.atlas.release.version")
    public String ATLAS_RELEASE_PUBLISHER_VERSION;


    @Inject
    public AdvisoryService(
            ErrataTool errataTool,
            GenerationRequestService generationRequestService,
            Koji koji,
            FailureNotifier failureNotifier) {
        this.errataTool = errataTool;
        this.generationRequestService = generationRequestService;
        this.koji = koji;
        this.failureNotifier = failureNotifier;
    }

    @Override
    public GenerationRequest requestGenerations(String advisoryId) {
        log.info("Processing advisory: {}...", advisoryId);

        Advisory advisory = fetchAdvisory(advisoryId);
        log.debug("Advisory '{}' status: {}, text-only: {}", advisory.id(), advisory.status(), advisory.isTextOnly());


        // todo we don't need to send anything, but for clarud
        if (!isValidAdvisoryState(advisory.status())) {
            log.info(
                    "Advisory '{}' has state '{}' which is not QE or SHIPPED_LIVE, ignoring",
                    advisory.id(),
                    advisory.status());
            GenerationRequest emptyRequest = createEmptyGenerationRequest();
            generationRequestService.requestGenerations(emptyRequest); //todo send?
            return emptyRequest;
        }

        // todo interface requries list, do we want list?
        List<Publisher> publishers = determinePublishers(advisory.status());
        log.debug("Advisory '{}' publishers: {}", advisory.id(), publishers);



        // Process based on advisory type
        // type + identifier
        List<Generation> generations = advisory.isTextOnly()
                ? handleTextOnlyAdvisory(advisory)
                : handleStandardAdvisory(advisory); 

        // Create and publish generation request
        GenerationRequest generationRequest = new GenerationRequest(
                TsidUtility.createUniqueGenerationRequestId(),
                publishers,
                generations);


        // Forward to generation request service
        generationRequestService.requestGenerations(generationRequest);

        log.info(
                "Advisory '{}' processed successfully: {} generation(s) requested",
                advisoryId,
                generations.size());
        return generationRequest;
    }

    private Advisory fetchAdvisory(String advisoryId) {
        try {
            return errataTool.getInfo(advisoryId);
        } catch (Exception e) {
            log.error("Failed to fetch advisory '{}' from Errata Tool: {}", advisoryId, e.getMessage(), e);
            throw notifyFailureAndThrow("Failed to fetch advisory from Errata Tool: " + advisoryId, e);
        }
    }

    /**
     * Validates if the advisory state should be processed.
     *
     * @param status Advisory status from Errata Tool
     * @return true if status is valid status that needs to be processed
     */
    private boolean isValidAdvisoryState(String status) {
        return "QE".equals(status) || "SHIPPED_LIVE".equals(status);
    }

    /**
     * Determines which publishers to use based on advisory state.
     *
     * @param status Advisory status
     * @return List of publishers (QE → Atlas Build, SHIPPED_LIVE → Atlas Release)
     */
    private List<Publisher> determinePublishers(String status) {
        List<Publisher> publishers = new ArrayList<>();

        switch (status) {
            case "QE" -> {
                publishers.add(new Publisher(ATLAS_BUILD_PUBLISHER_NAME, ATLAS_BUILD_PUBLISHER_VERSION));
                log.debug("QE advisory: using Atlas Build publisher");
            }
            case "SHIPPED_LIVE" -> {
                publishers.add(new Publisher(ATLAS_RELEASE_PUBLISHER_NAME, ATLAS_RELEASE_PUBLISHER_VERSION));
                log.debug("SHIPPED_LIVE advisory: using Atlas Release publisher");
            }
            default -> {
                log.warn("Unknown advisory status '{}', skipping", status);
            }
        }

        return publishers;
    }

    /**
     * Handles text-only advisories.
     * Currently logs and returns empty list (future enhancement).
     *
     * @param advisory The advisory to process
     * @return Empty list of generations
     */
    private List<Generation> handleTextOnlyAdvisory(Advisory advisory) {
        log.info("Advisory '{}' is text-only, skipping (not yet implemented)", advisory.id());
        // TODO: Implement text-only advisory handling in future iteration
        return Collections.emptyList();
    }

    /**
     * Handles standard advisories with attached builds.
     * Fetches builds, categorizes by type, resolves identifiers, and creates generations.
     *
     * @param advisory The advisory to process
     * @return List of generations for the advisory's builds
     */
    private List<Generation> handleStandardAdvisory(Advisory advisory) {
        log.info("Processing standard advisory '{}'", advisory.id());

        // Fetch all builds attached to the advisory
        List<Build> attachedBuilds = fetchBuilds(advisory.id());
        log.debug("Advisory '{}' has {} build(s) attached", advisory.id(), attachedBuilds.size());

        if (attachedBuilds.isEmpty()) {
            log.info("Advisory '{}' has no builds attached, returning empty generations", advisory.id());
            return Collections.emptyList();
        }

        // Categorize builds by type (RPM vs Container)
        Map<String, List<Build>> buildsByType = categorizeBuilds(attachedBuilds);
        log.debug("Build types found: {}", buildsByType.keySet());

        // Process each build type and create generations
        List<Generation> generations = new ArrayList<>();

        // Process RPM builds
        if (buildsByType.containsKey("RPM")) {
            List<Generation> rpmGenerations = processRpmBuilds(buildsByType.get("RPM"));
            generations.addAll(rpmGenerations);
            log.debug("Created {} RPM generation(s)", rpmGenerations.size());
        }

        // Process Container builds
        if (buildsByType.containsKey("CONTAINER")) {
            List<Generation> containerGenerations = processContainerBuilds(buildsByType.get("CONTAINER"));
            generations.addAll(containerGenerations);
            log.debug("Created {} container generation(s)", containerGenerations.size());
        }

        return generations;
    }

    /**
     * Fetches builds from Errata Tool with proper exception handling.
     *
     * @param advisoryId The advisory ID
     * @return List of builds (never null)
     * @throws AdvisoryProcessingException if fetching fails
     */
    private List<Build> fetchBuilds(String advisoryId) {
        try {
            return errataTool.fetchBuilds(advisoryId);
        } catch (Exception e) {
            log.error("Failed to fetch builds for advisory '{}': {}", advisoryId, e.getMessage(), e);
            throw notifyFailureAndThrow("Failed to fetch builds from Errata Tool for advisory: " + advisoryId, e);
        }
    }

    /**
     * Categorizes builds by their type.
     *
     * @param builds List of builds to categorize
     * @return Map of build type to list of builds
     */
    private Map<String, List<Build>> categorizeBuilds(List<Build> builds) {
        Map<String, List<Build>> categorized = new HashMap<>();

        for (Build build : builds) {
            String type = build.type();
            if (type == null) {
                log.warn("Build {} has null type, skipping", build.id());
                continue;
            }
            
            // key for each buildtype
            categorized.computeIfAbsent(type, k -> new ArrayList<>()).add(build);
        }

        return categorized;
    }

    /**
     * Processes RPM builds and creates generations.
     * For RPM builds, the identifier is the build ID.
     *
     * @param rpmBuilds List of RPM builds
     * @return List of generations for RPM builds
     */
    private List<Generation> processRpmBuilds(List<Build> rpmBuilds) {
        log.debug("Processing {} RPM build(s)", rpmBuilds.size());

        return rpmBuilds.stream().map(build -> {
            String identifier = String.valueOf(build.id());
            log.debug("RPM build {}: using build ID as identifier: {}", build.nvr(), identifier);

            return new Generation(
                    TsidUtility.createUniqueGenerationId(),
                    new GenerationTarget("RPM", identifier));
        }).collect(Collectors.toList());
    }

    /**
     * Processes container builds and creates generations.
     * For container builds, fetches image digests from Koji and uses them as identifiers.
     *
     * @param containerBuilds List of container builds
     * @return List of generations for container builds
     */
    private List<Generation> processContainerBuilds(List<Build> containerBuilds) {
        log.debug("Processing {} container build(s)", containerBuilds.size());

        // Collect build IDs for batch Koji query
        List<Long> buildIds = containerBuilds.stream().map(Build::id).collect(Collectors.toList());

        // Fetch image names/digests from Koji
        Map<Long, String> imageDigests = fetchImageDigests(buildIds);
        log.debug("Retrieved {} image digest(s) from Koji", imageDigests.size());

        // Create generations for container builds with valid digests
        List<Generation> generations = new ArrayList<>();
        for (Build build : containerBuilds) {
            String imageDigest = imageDigests.get(build.id());

            if (imageDigest == null || imageDigest.isEmpty()) {
                log.warn("No image digest found for container build {} ({}), skipping", build.id(), build.nvr());
                continue;
            }

            log.debug("Container build {}: using image digest as identifier: {}", build.nvr(), imageDigest);

            generations.add(
                    new Generation(
                            TsidUtility.createUniqueGenerationId(),
                            new GenerationTarget("CONTAINER", imageDigest)));
        }

        return generations;
    }

    /**
     * Fetches image digests from Koji with proper exception handling.
     *
     * @param buildIds List of build IDs
     * @return Map of build ID to image digest (never null)
     * @throws AdvisoryProcessingException if fetching fails
     */
    private Map<Long, String> fetchImageDigests(List<Long> buildIds) {
        try {
            return koji.getImageNames(buildIds);
        } catch (Exception e) {
            log.error("Failed to fetch image digests from Koji for builds {}: {}", buildIds, e.getMessage(), e);
            throw notifyFailureAndThrow("Failed to fetch container image digests from Koji", e);
        }
    }

    /**
     * Notifies about failure and throws AdvisoryProcessingException.
     * Centralizes failure notification logic.
     * This method never returns normally - it always throws.
     *
     * @param message Error message
     * @param cause Original exception
     * @return AdvisoryProcessingException to allow throw statement in caller
     * @throws AdvisoryProcessingException always
     */
    private AdvisoryProcessingException notifyFailureAndThrow(String message, Exception cause) {
        FailureSpec failure = FailureUtility.buildFailureSpecFromException(cause);
        failureNotifier.notify(failure, null, null); // todo 
        throw new AdvisoryProcessingException(message, cause);
    }

    private GenerationRequest createEmptyGenerationRequest() {
        return new GenerationRequest(
                TsidUtility.createUniqueGenerationRequestId(),
                Collections.emptyList(),
                Collections.emptyList());
    }
}
