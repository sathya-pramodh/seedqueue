package me.contaria.seedqueue;

import me.contaria.seedqueue.mixin.accessor.UtilAccessor;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wraps the server executor to allow for using seperate executors while a server is generating in queue.
 * This allows for modifying thread priorities and parallelism of executors used while in queue to help manage performance.
 * <p>
 * The backing {@link ExecutorService}'s are created lazily and should be shut down after a SeedQueue session.
 * This should happen AFTER all servers have been shut down!
 *
 * @see SeedQueueConfig#backgroundExecutorThreadPriority
 * @see SeedQueueConfig#backgroundExecutorThreads
 * @see SeedQueueConfig#wallExecutorThreadPriority
 * @see SeedQueueConfig#wallExecutorThreads
 */
public class SeedQueueExecutorWrapper implements Executor {
    /**
     * Executor used by servers while they are in queue.
     * Redirects to the backing executors depending on the current state.
     */
    public static final Executor SEEDQUEUE_EXECUTOR = command -> getSeedqueueExecutor().execute(command);
    public static final Executor LOCKED_EXECUTOR = command -> getOrCreateLockedExecutor().execute(command);

    private static ExecutorService SEEDQUEUE_BACKGROUND_EXECUTOR;
    private static ExecutorService SEEDQUEUE_LOCKED_EXECUTOR;
    private static ExecutorService SEEDQUEUE_WALL_EXECUTOR;

    private final Executor originalExecutor;
    private Executor executor;

    public SeedQueueExecutorWrapper(Executor originalExecutor) {
        this.executor = this.originalExecutor = originalExecutor;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        this.executor.execute(command);
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public void resetExecutor() {
        this.setExecutor(this.originalExecutor);
    }

    private static Executor getSeedqueueExecutor() {
        // if Max Generating Seeds is set to 0 while not on wall,
        // this will ensure the background executor is never created
        if (SeedQueue.isOnWall() || SeedQueue.config.maxConcurrently == 0) {
            return getOrCreateWallExecutor();
        }
        return getOrCreateBackgroundExecutor();
    }

    private synchronized static Executor getOrCreateBackgroundExecutor() {
        if (SEEDQUEUE_BACKGROUND_EXECUTOR == null) {
            SEEDQUEUE_BACKGROUND_EXECUTOR = createExecutor("SeedQueue", SeedQueue.config.getBackgroundExecutorThreads(), SeedQueue.config.backgroundExecutorThreadPriority);
        }
        return SEEDQUEUE_BACKGROUND_EXECUTOR;
    }

    private synchronized static Executor getOrCreateWallExecutor() {
        if (SEEDQUEUE_WALL_EXECUTOR == null) {
            SEEDQUEUE_WALL_EXECUTOR = createExecutor("SeedQueue Wall", SeedQueue.config.getWallExecutorThreads(), SeedQueue.config.wallExecutorThreadPriority);
        }
        return SEEDQUEUE_WALL_EXECUTOR;
    }

    public synchronized static Executor getOrCreateLockedExecutor() {
        if (SEEDQUEUE_LOCKED_EXECUTOR == null) {
            SEEDQUEUE_LOCKED_EXECUTOR = createExecutor("SeedQueue Locked", SeedQueue.config.getLockedExecutorThreads(), SeedQueue.config.lockedExecutorThreadPriority);
        }
        return SEEDQUEUE_LOCKED_EXECUTOR;
    }


    // see Util#createWorker
    private static ExecutorService createExecutor(String name, int threads, int priority) {
        AtomicInteger threadCount = new AtomicInteger();
        return new ForkJoinPool(threads, pool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("Worker-" + name + "-" + threadCount.getAndIncrement());
            thread.setPriority(priority);
            return thread;
        }, UtilAccessor::seedQueue$uncaughtExceptionHandler, true);
    }

    /**
     * Shuts down and removes the SeedQueue specific {@link ExecutorService}s.
     */
    public synchronized static void shutdownExecutors() {
        if (SEEDQUEUE_BACKGROUND_EXECUTOR != null) {
            UtilAccessor.seedQueue$attemptShutdown(SEEDQUEUE_BACKGROUND_EXECUTOR);
            SEEDQUEUE_BACKGROUND_EXECUTOR = null;
        }
        if (SEEDQUEUE_WALL_EXECUTOR != null) {
            UtilAccessor.seedQueue$attemptShutdown(SEEDQUEUE_WALL_EXECUTOR);
            SEEDQUEUE_WALL_EXECUTOR = null;
        }
    }
}
