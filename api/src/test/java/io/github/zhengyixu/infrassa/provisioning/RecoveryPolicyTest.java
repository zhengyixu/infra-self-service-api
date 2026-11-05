package io.github.zhengyixu.infrassa.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zhengyixu.infrassa.job.JobPhase;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The ADR 0001 recovery table. .NET analogy: an xUnit [Theory] with [InlineData] rows. */
@Disabled("TODO(you): implement RecoveryPolicy.decide, then delete this line")
class RecoveryPolicyTest {

    private final RecoveryPolicy policy = new RecoveryPolicy(3);

    @ParameterizedTest(name = "{0}, attempt {1} -> {2}")
    @CsvSource({
        "PLANNING,    1, REQUEUE",
        "PLANNING,    2, REQUEUE",
        "PLANNING,    3, FAIL", // third claim also expired: stop retrying
        "PLANNING,    4, FAIL",
        "APPLYING,    1, INTERRUPT", // infrastructure may be half-changed
        "APPLYING,    3, INTERRUPT",
        "CONFIGURING, 1, INTERRUPT",
    })
    void followsTheRecoveryTable(JobPhase phase, int attempt, RecoveryAction expected) {
        assertThat(policy.decide(phase, attempt)).isEqualTo(expected);
    }
}
