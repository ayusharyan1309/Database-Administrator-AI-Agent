package com.optiq.config;

import com.optiq.scheduler.SlowQueryPoller;
import com.optiq.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives the polling loop from the interval saved in Settings.
 *
 * A plain {@code @Scheduled(fixedDelayString = "...")} resolves its interval
 * once, when the bean is created, so an interval changed in Settings would not
 * apply until the next restart. Registering a trigger task instead lets the
 * interval be read fresh after every cycle, which is what makes Settings the
 * single source of truth for how often the daemon runs.
 *
 * The application property is only the seed and the fallback: it is used when
 * nothing has been saved in Settings yet, or when the saved value is unusable.
 */
@Configuration
public class PollingScheduleConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PollingScheduleConfig.class);

    /** Refuse to spin: a interval below this would hammer the target database. */
    private static final long MIN_INTERVAL_MS = 1_000L;

    private final SlowQueryPoller poller;
    private final SettingsService settings;

    /** Seed value only — the live interval comes from Settings. */
    @Value("${optiq.polling.interval-ms:300000}")
    private long defaultIntervalMs;

    /** Last interval actually scheduled, so a change can be logged once. */
    private final AtomicLong lastLoggedInterval = new AtomicLong(-1);

    public PollingScheduleConfig(SlowQueryPoller poller, SettingsService settings) {
        this.poller = poller;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("optiq-poll-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);

        registrar.addTriggerTask(poller::pollForSlowQueries, new SettingsDrivenTrigger());
    }

    /** Schedules the next poll one configured interval after the last one finished. */
    private class SettingsDrivenTrigger implements Trigger {
        @Override
        public Instant nextExecution(TriggerContext context) {
            long interval = resolveIntervalMs();
            Instant last = context.lastCompletion();
            return (last != null ? last : Instant.now()).plusMillis(interval);
        }
    }

    private long resolveIntervalMs() {
        long interval = settings.getPollIntervalMs(defaultIntervalMs);

        if (interval < MIN_INTERVAL_MS) {
            log.warn("Polling interval of {}ms is below the {}ms minimum; using {}ms",
                interval, MIN_INTERVAL_MS, MIN_INTERVAL_MS);
            interval = MIN_INTERVAL_MS;
        }

        long previous = lastLoggedInterval.getAndSet(interval);
        if (previous != interval) {
            if (previous < 0) log.info("Polling every {}ms (from Settings)", interval);
            else log.info("Polling interval changed from {}ms to {}ms (from Settings)", previous, interval);
        }
        return interval;
    }
}
