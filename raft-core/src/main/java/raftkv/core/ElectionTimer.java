package raftkv.core;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//how nodes know that leader has died
public class ElectionTimer {
    private static final Logger logger = LoggerFactory.getLogger(ElectionTimer.class);
    
    private final ScheduledExecutorService scheduler;
    private final Runnable callback;
    private final int minTimeoutMs;
    private final int maxTimeoutMs;
    private final Random random = new Random();
    private ScheduledFuture<?> future;

    public ElectionTimer(ScheduledExecutorService scheduler, Runnable callback, int minTimeoutMs, int maxTimeoutMs) {
        this.scheduler = scheduler;
        this.callback = callback;
        this.minTimeoutMs = minTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
    }

    public synchronized void reset() {
        if (future != null) {
            future.cancel(false);
        }
        int timeout = minTimeoutMs + random.nextInt(maxTimeoutMs - minTimeoutMs + 1);
        future = scheduler.schedule(() -> {
            try {
                callback.run();
            } catch (Exception e) {
                logger.error("Error executing election timeout callback", e);
            }
        }, timeout, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }
}
