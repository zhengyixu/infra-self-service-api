package io.github.zhengyixu.infrassa.environment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /environments}. Validated by Bean Validation before the controller runs
 * (like DataAnnotations + [ApiController] automatic 400s in ASP.NET Core).
 *
 * @param name used in AWS resource names, tags and the Terraform state key, so DNS-label style
 * @param instanceType allow-listed to keep real-AWS cost predictable; defaults to t3.micro
 * @param instanceCount defaults to 1
 */
public record CreateEnvironmentRequest(
        @NotNull
        @Pattern(
                regexp = "^[a-z][a-z0-9-]{1,30}[a-z0-9]$",
                message = "must be 3-32 characters: lowercase letters, digits and hyphens,"
                        + " starting with a letter and not ending with a hyphen")
        String name,

        @Pattern(regexp = "^t3\\.(micro|small)$", message = "must be one of: t3.micro, t3.small")
        String instanceType,

        @Min(1) @Max(3) Integer instanceCount) {

    public static final String DEFAULT_INSTANCE_TYPE = "t3.micro";
    public static final int DEFAULT_INSTANCE_COUNT = 1;

    /** Compact constructor: runs during JSON deserialization, before validation. */
    public CreateEnvironmentRequest {
        if (instanceType == null) {
            instanceType = DEFAULT_INSTANCE_TYPE;
        }
        if (instanceCount == null) {
            instanceCount = DEFAULT_INSTANCE_COUNT;
        }
    }
}
