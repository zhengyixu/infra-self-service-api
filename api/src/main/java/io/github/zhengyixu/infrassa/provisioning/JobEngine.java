package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.environment.EnvironmentRepository;
import io.github.zhengyixu.infrassa.job.ClaimedJob;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The in-process worker pool (ADR 0001). Three periodic tasks:
 *
 * <ul>
 *   <li><b>poll</b>: while a slot is free, claim the oldest PENDING job and start it on a new
 *       virtual thread;
 *   <li><b>heartbeat</b>: renew the lease of every job running here; a renewal that matches no row
 *       means the lease was lost, so that job is stopped;
 *   <li><b>sweep</b>: recover expired leases ({@link RecoverySweep}), at startup and periodically.
 * </ul>
 *
 * <p>{@link SmartLifecycle}: Spring starts this after every other bean is ready and stops it
 * before the web server and the connection pool. .NET analogy: a {@code BackgroundService} /
 * {@code IHostedService} with {@code StartAsync}/{@code StopAsync}.
 */
@Component
@ConditionalOnProperty(name = "infrassa.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class JobEngine implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobEngine.class);

    private final JobEngineProperties properties;
    private final JobTransitions transitions;
    private final RecoverySweep sweep;
    private final EnvironmentRepository environments;
    private final ProvisioningRunner runner;
    private final String workerId = workerId();
    private final Semaphore slots;
    private final Map<UUID, JobExecution> active = new ConcurrentHashMap<>();

    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ExecutorService workers;

    public JobEngine(
            JobEngineProperties properties,
            JobTransitions transitions,
            RecoverySweep sweep,
            EnvironmentRepository environments,
            ProvisioningRunner runner) {
        this.properties = properties;
        this.transitions = transitions;
        this.sweep = sweep;
        this.environments = environments;
        this.runner = runner;
        this.slots = new Semaphore(properties.maxConcurrentJobs());
    }

    @Override
    public void start() {
        // One virtual thread per job: blocking on a child process costs no platform thread.
        workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("job-", 0).factory());
        // Separate scheduler threads, so a slow poll never delays a heartbeat.
        scheduler = Executors.newScheduledThreadPool(
                3, Thread.ofVirtual().name("job-engine-", 0).factory());
        running = true;

        schedule("sweep", this::recoverExpiredLeases, Duration.ZERO, properties.sweepInterval());
        schedule("poll", this::poll, properties.pollInterval(), properties.pollInterval());
        schedule("heartbeat", this::heartbeat, properties.heartbeatInterval(), properties.heartbeatInterval());
        log.info(
                "Job engine started: workerId={} maxConcurrentJobs={} runner={}",
                workerId,
                properties.maxConcurrentJobs(),
                runner.getClass().getSimpleName());
    }

    /**
     * Stops claiming, stops running jobs (their child processes get SIGTERM) and waits up to the
     * grace period. Nothing is recorded for stopped jobs: their leases expire and the sweep on a
     * running instance (or on the next start) classifies them by phase.
     */
    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        if (!active.isEmpty()) {
            log.warn("Shutting down with {} running job(s); stopping them: {}", active.size(), active.keySet());
        }
        active.values().forEach(execution -> execution.abort("shutdown"));
        workers.shutdown();
        try {
            if (!workers.awaitTermination(properties.shutdownGracePeriod().toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Jobs still stopping after {}: {}", properties.shutdownGracePeriod(), active.keySet());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Claims and starts jobs while this instance has free slots. */
    void poll() {
        while (running && slots.tryAcquire()) {
            Optional<ClaimedJob> claimed;
            try {
                claimed = transitions.claimNext(workerId, properties.leaseDuration());
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (claimed.isEmpty()) {
                slots.release();
                return;
            }
            start(claimed.get());
        }
    }

    void heartbeat() {
        for (JobExecution execution : active.values()) {
            try {
                if (!transitions.renewLease(execution.lease(), properties.leaseDuration())) {
                    log.warn(
                            "Lease lost for job {}; stopping it",
                            execution.lease().jobId());
                    execution.abort("lease lost");
                }
            } catch (RuntimeException e) {
                // Database unreachable: keep the job running and try again next time. If the lease
                // expires meanwhile, the next fenced update fails and the job stops then.
                log.warn("Heartbeat failed for job {}", execution.lease().jobId(), e);
            }
        }
    }

    void recoverExpiredLeases() {
        sweep.sweep();
    }

    private void start(ClaimedJob claimed) {
        UUID jobId = claimed.job().id();
        var execution = new JobExecution(claimed, transitions, environments, runner, properties.leaseDuration());
        active.put(jobId, execution); // before the thread starts, so the first heartbeat sees it
        try {
            workers.execute(() -> {
                try {
                    execution.run();
                } finally {
                    active.remove(jobId);
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Shutting down between claim and start: the lease expires and recovery requeues it.
            active.remove(jobId);
            slots.release();
        }
    }

    /**
     * A periodic task that survives its own failures. {@code scheduleWithFixedDelay} silently
     * cancels all future runs of a task that throws, so a single database hiccup would otherwise
     * stop polling forever.
     */
    private void schedule(String name, Runnable task, Duration initialDelay, Duration delay) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Job engine task '{}' failed; it will run again", name, e);
            }
        };
        scheduler.scheduleWithFixedDelay(guarded, initialDelay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Unique per process start: host, PID and a random suffix (in a container every process is
     * PID 1, so host + PID alone repeats across restarts). Stored in jobs.claimed_by.
     */
    private static String workerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        byte[] suffix = new byte[3];
        ThreadLocalRandom.current().nextBytes(suffix);
        return host + "/" + ProcessHandle.current().pid() + "/" + HexFormat.of().formatHex(suffix);
    }
}
