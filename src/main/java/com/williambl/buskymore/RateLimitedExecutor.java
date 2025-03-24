package com.williambl.buskymore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;

public class RateLimitedExecutor implements Executor, AutoCloseable {
    public static final Logger LOGGER = LoggerFactory.getLogger(RateLimitedExecutor.class);

    private final ScheduledExecutorService scheduler;
    private final Executor taskExecutor;
    private final ConcurrentLinkedDeque<Runnable> taskQueue = new ConcurrentLinkedDeque<>();
    private CompletableFuture<Void> lastTask = CompletableFuture.completedFuture(null);
    private Instant rateLimitedUntil;
    private TimeWithUnit extraDelay = null;

    public RateLimitedExecutor(long maxTasksPer, Duration duration, Executor taskExecutor) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        var durationBetweenTasks = duration.dividedBy(maxTasksPer);
        TimeWithUnit delay = this.getDelay(durationBetweenTasks);
        this.scheduler.scheduleWithFixedDelay(this::consumeTask, 0, delay.duration(), delay.unit());
        this.taskExecutor = taskExecutor;
    }

    private record TimeWithUnit(long duration, TimeUnit unit) { }

    private TimeWithUnit getDelay(Duration durationBetweenTasks) {
        long delay;
        TimeUnit unit;
        if (durationBetweenTasks.getSeconds() < 1000) {
            unit = TimeUnit.MILLISECONDS;
            delay = durationBetweenTasks.toMillis();
        } else {
            unit = TimeUnit.SECONDS;
            delay = durationBetweenTasks.getSeconds();
        }
        return new TimeWithUnit(delay, unit);
    }

    public void updateLimits(int remaining, Duration untilReset) {
        this.extraDelay = this.getDelay(untilReset.dividedBy(remaining));
    }

    public void markRateLimited(Instant until) {
        this.rateLimitedUntil = until;
    }

    private void consumeTask() {
        if (!this.lastTask.isDone()) {
            return;
        }

        if (this.rateLimitedUntil != null) {
            if (Instant.now().isBefore(this.rateLimitedUntil)) {
                return;
            }
            this.rateLimitedUntil = null;
        }

        var task = this.taskQueue.poll();
        if (task == null) {
            return;
        }

        LOGGER.info("Running a task, {} tasks left", this.taskQueue.size());
        this.lastTask = CompletableFuture.runAsync(task, this.taskExecutor);

        if (this.extraDelay != null) {
            this.markRateLimited(Instant.now().plus(this.extraDelay.duration(), this.extraDelay.unit().toChronoUnit()));
            LOGGER.info("We'll hit rate limit if we don't slow down, so waiting {} ms until next task", TimeUnit.MILLISECONDS.convert(this.extraDelay.duration(), this.extraDelay.unit()));
        }
    }

    public void retry(Runnable command) {
        this.taskQueue.addFirst(command);
    }

    @Override
    public void execute(Runnable command) {
        this.taskQueue.add(command);
    }

    @Override
    public void close() {
        this.scheduler.close();
    }
}
