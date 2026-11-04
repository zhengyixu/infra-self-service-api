package io.github.zhengyixu.infrassa.environment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * HTTP layer only: validation, status codes, mapping to response DTOs. Errors are turned into
 * RFC 9457 problem details by {@link io.github.zhengyixu.infrassa.web.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/environments")
@Tag(name = "Environments")
public class EnvironmentController {

    private final EnvironmentService service;

    public EnvironmentController(EnvironmentService service) {
        this.service = service;
    }

    /**
     * 202 Accepted, not 201 Created: the environment record exists, but provisioning runs in the
     * background. Clients poll the Location URL until status is READY or FAILED.
     */
    @PostMapping
    @Operation(summary = "Request a new environment; provisioning runs in the background")
    public ResponseEntity<EnvironmentResponse> create(@Valid @RequestBody CreateEnvironmentRequest request) {
        EnvironmentResponse body = EnvironmentResponse.from(service.request(request));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.accepted().location(location).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an environment and the status of its latest job")
    public EnvironmentResponse get(@PathVariable UUID id) {
        return EnvironmentResponse.from(service.get(id));
    }
}
