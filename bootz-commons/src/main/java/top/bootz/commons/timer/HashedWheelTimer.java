package top.bootz.commons.timer;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

import top.bootz.commons.helper.DateHelper;
import top.bootz.commons.helper.RandomHelper;

/**
 * 将netty实现的hashedWheelTimer算法搬过来，将一些netty自己实现的类转为java原生类；
 * <p>
 * 高并发大任务量时，性能上可能会有微小影响，但是避免了需要引入大量netty依赖的问题
 * 
 * @author Zhangq
 *
 */
public class HashedWheelTimer {

	private static final Logger logger = LoggerFactory.getLogger(HashedWheelTimer.class);

	private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
	private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
	private static final int INSTANCE_COUNT_LIMIT = 64;
	private static final long MILLISECOND_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

	private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER = AtomicIntegerFieldUpdater
			.newUpdater(HashedWheelTimer.class, "workerState");

	private final Worker worker = new Worker();

	private final Thread workerThread;

	public static final int WORKER_STATE_INIT = 0;
	public static final int WORKER_STATE_STARTED = 1;
	public static final int WORKER_STATE_SHUTDOWN = 2;

	@SuppressWarnings("unused")
	private volatile int workerState; // 0 - init, 1 - started, 2 - shut down

	/** 每格执行时间 */
	private final long tickDuration;

	/** 一轮一共会被分隔为的多少格 */
	private final HashedWheelBucket[] wheel;

	private final int mask;
	private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
	private final Queue<HashedWheelTimeout> timeouts = new ConcurrentLinkedQueue<HashedWheelTimer.HashedWheelTimeout>();
	private final Queue<HashedWheelTimeout> cancelledTimeouts = new ConcurrentLinkedQueue<HashedWheelTimer.HashedWheelTimeout>();

	/** 当前timer里剩余的未执行任务数 */
	private final AtomicLong pendingTimeouts = new AtomicLong(0);

	/** 允许最大的等待执行任务数，超过这个数量，程序会拒绝该任务，并抛出错误 */
	private final long maxPendingTimeouts;

	/** 当前timer的唯一标识 */
	private int id;
	private static final AtomicInteger INSTANCE_ID = new AtomicInteger(1); 
	
	/** timer开始转动的时间 */
	private volatile long startTime;

	public HashedWheelTimer() {
		this(Executors.defaultThreadFactory());
	}

	public HashedWheelTimer(long tickDuration, TimeUnit unit) {
		this(Executors.defaultThreadFactory(), tickDuration, unit);
	}

	public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
		this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
	}

	public HashedWheelTimer(ThreadFactory threadFactory) {
		this(threadFactory, 100, TimeUnit.MILLISECONDS);
	}

	public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
		this(threadFactory, tickDuration, unit, 512);
	}

	public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit,
			int ticksPerWheel) {
		this(threadFactory, tickDuration, unit, ticksPerWheel, -1);
	}

	public HashedWheelTimer(ThreadFactory threadFactory, long tickDuration, TimeUnit unit,
			int ticksPerWheel, long maxPendingTimeouts) {

		this.id = INSTANCE_ID.getAndIncrement();
		
		if (threadFactory == null) {
			throw new NullPointerException("threadFactory");
		}
		if (unit == null) {
			throw new NullPointerException("unit");
		}
		if (tickDuration <= 0) {
			throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
		}
		if (ticksPerWheel <= 0) {
			throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
		}

		// Normalize ticksPerWheel to power of two and initialize the wheel.
		wheel = createWheel(ticksPerWheel);
		mask = wheel.length - 1;

		// Convert tickDuration to nanos.
		long duration = unit.toNanos(tickDuration);

		// Prevent overflow.
		if (duration >= Long.MAX_VALUE / wheel.length) {
			throw new IllegalArgumentException(
					String.format("tickDuration: %d (expected: 0 < tickDuration in nanos < %d", tickDuration,
							Long.MAX_VALUE / wheel.length));
		}

		if (duration < MILLISECOND_NANOS) {
			if (logger.isWarnEnabled()) {
				logger.warn("Configured tickDuration {} smaller then {}, using 1ms.", tickDuration, MILLISECOND_NANOS);
			}
			this.tickDuration = MILLISECOND_NANOS;
		} else {
			this.tickDuration = duration;
		}

		workerThread = threadFactory.newThread(worker);

		this.maxPendingTimeouts = maxPendingTimeouts;

		if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT
				&& WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
			reportTooManyInstances();
		}
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			super.finalize();
		} finally {
			// This object is going to be GCed and it is assumed the ship has
			// sailed to do a proper shutdown. If
			// we have not yet shutdown then we want to make sure we decrement
			// the active instance count.
			if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
				INSTANCE_COUNTER.decrementAndGet();
			}
		}
	}

	private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
		if (ticksPerWheel <= 0) {
			throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
		}
		if (ticksPerWheel > 1073741824) {
			throw new IllegalArgumentException("ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
		}

		ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
		HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
		for (int i = 0; i < wheel.length; i++) {
			wheel[i] = new HashedWheelBucket();
		}
		return wheel;
	}

	private static int normalizeTicksPerWheel(int ticksPerWheel) {
		int normalizedTicksPerWheel = 1;
		while (normalizedTicksPerWheel < ticksPerWheel) {
			normalizedTicksPerWheel <<= 1;
		}
		return normalizedTicksPerWheel;
	}

	/**
	 * Starts the background thread explicitly. The background thread will start
	 * automatically on demand even if you did not call this method.
	 *
	 * @throws IllegalStateException
	 *             if this timer has been {@linkplain #stop() stopped} already
	 */
	public void start() {
		switch (WORKER_STATE_UPDATER.get(this)) {
		case WORKER_STATE_INIT:
			if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
				workerThread.start();
			}
			break;
		case WORKER_STATE_STARTED:
			break;
		case WORKER_STATE_SHUTDOWN:
			throw new IllegalStateException("cannot be started once stopped");
		default:
			throw new Error("Invalid WorkerState");
		}

		// Wait until the startTime is initialized by the worker.
		while (startTime == 0) {
			try {
				startTimeInitialized.await();
			} catch (InterruptedException ignore) {
				// Ignore - it will be ready very soon.
			}
		}
	}

	public Set<HashedWheelTimeout> stop() {
		if (Thread.currentThread() == workerThread) {
			throw new IllegalStateException(HashedWheelTimer.class.getSimpleName() + ".stop() cannot be called from "
					+ TimerTask.class.getSimpleName());
		}

		if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
			// workerState can be 0 or 2 at this moment - let it always be 2.
			if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
				INSTANCE_COUNTER.decrementAndGet();
			}

			return Collections.emptySet();
		}

		try {
			boolean interrupted = false;
			while (workerThread.isAlive()) {
				workerThread.interrupt();
				try {
					workerThread.join(100);
				} catch (InterruptedException ignored) {
					interrupted = true;
				}
			}

			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		} finally {
			INSTANCE_COUNTER.decrementAndGet();
		}
		return worker.unprocessedTimeouts();
	}

	public HashedWheelTimeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
		if (task == null) {
			throw new NullPointerException("task");
		}
		if (unit == null) {
			throw new NullPointerException("unit");
		}

		long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

		if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
			pendingTimeouts.decrementAndGet();
			throw new RejectedExecutionException("Number of pending timeouts (" + pendingTimeoutsCount
					+ ") is greater than or equal to maximum allowed pending " + "timeouts (" + maxPendingTimeouts
					+ ")");
		}

		start();

		// Add the timeout to the timeout queue which will be processed on the
		// next tick.
		// During processing all the queued HashedWheelTimeouts will be added to
		// the correct HashedWheelBucket.
		long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

		// Guard against overflow.
		if (delay > 0 && deadline < 0) {
			deadline = Long.MAX_VALUE;
		}
		HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
		timeouts.add(timeout);
		return timeout;
	}

	/**
	 * Returns the number of pending timeouts of this {@link Timer}.
	 */
	public long pendingTimeouts() {
		return pendingTimeouts.get();
	}

	private static void reportTooManyInstances() {
		if (logger.isErrorEnabled()) {
			String resourceType = HashedWheelTimer.class.getSimpleName();
			logger.error("You are creating too many " + resourceType + " instances. " + resourceType
					+ " is a shared resource that must be reused across the JVM,"
					+ "so that only a few instances are created.");
		}
	}

	private final class Worker implements Runnable {
		private final Set<HashedWheelTimeout> unprocessedTimeouts = new HashSet<HashedWheelTimeout>();

		private long tick;

		@Override
		public void run() {
			// Initialize the startTime.
			startTime = System.nanoTime();
			if (startTime == 0) {
				// We use 0 as an indicator for the uninitialized value here, so
				// make sure it's not 0 when initialized.
				startTime = 1;
			}

			// Notify the other threads waiting for the initialization at
			// start().
			startTimeInitialized.countDown();

			do {
				final long deadline = waitForNextTick();
				if (deadline > 0) {
					int idx = (int) (tick & mask);
					processCancelledTasks();
					HashedWheelBucket bucket = wheel[idx];
					transferTimeoutsToBuckets();
					bucket.expireTimeouts(deadline);
					tick++;
				}
			} while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

			// Fill the unprocessedTimeouts so we can return them from stop()
			// method.
			for (HashedWheelBucket bucket : wheel) {
				bucket.clearTimeouts(unprocessedTimeouts);
			}
			for (;;) {
				HashedWheelTimeout timeout = timeouts.poll();
				if (timeout == null) {
					break;
				}
				if (!timeout.isCancelled()) {
					unprocessedTimeouts.add(timeout);
				}
			}
			processCancelledTasks();
		}

		private void transferTimeoutsToBuckets() {
			// transfer only max. 100000 timeouts per tick to prevent a thread
			// to stale the workerThread when it just
			// adds new timeouts in a loop.
			for (int i = 0; i < 100000; i++) {
				HashedWheelTimeout timeout = timeouts.poll();
				if (timeout == null) {
					// all processed
					break;
				}
				if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
					// Was cancelled in the meantime.
					continue;
				}

				long calculated = timeout.deadline / tickDuration;
				timeout.remainingRounds = (calculated - tick) / wheel.length;

				final long ticks = Math.max(calculated, tick); // Ensure we
																// don't
																// schedule for
																// past.
				int stopIndex = (int) (ticks & mask);

				HashedWheelBucket bucket = wheel[stopIndex];
				bucket.addTimeout(timeout);
			}
		}

		private void processCancelledTasks() {
			for (;;) {
				HashedWheelTimeout timeout = cancelledTimeouts.poll();
				if (timeout == null) {
					// all processed
					break;
				}
				try {
					timeout.remove();
				} catch (Exception t) {
					if (logger.isWarnEnabled()) {
						logger.warn("An exception was thrown while process a cancellation task", t);
					}
				}
			}
		}

		/**
		 * calculate goal nanoTime from startTime and current tick number, then
		 * wait until that goal has been reached.
		 * 
		 * @return Long.MIN_VALUE if received a shutdown request, current time
		 *         otherwise (with Long.MIN_VALUE changed by +1)
		 */
		private long waitForNextTick() {
			long deadline = tickDuration * (tick + 1);

			for (;;) {
				final long currentTime = System.nanoTime() - startTime;
				long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

				if (sleepTimeMs <= 0) {
					if (currentTime == Long.MIN_VALUE) {
						return -Long.MAX_VALUE;
					} else {
						return currentTime;
					}
				}

				sleepTimeMs = sleepTimeMs / 10 * 10;

				try {
					Thread.sleep(sleepTimeMs);
				} catch (InterruptedException ignored) {
					if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
						return Long.MIN_VALUE;
					}
				}
			}
		}

		public Set<HashedWheelTimeout> unprocessedTimeouts() {
			return Collections.unmodifiableSet(unprocessedTimeouts);
		}
	}

	private static final class HashedWheelTimeout {

		private static final int ST_INIT = 0;
		private static final int ST_CANCELLED = 1;
		private static final int ST_EXPIRED = 2;
		private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER = AtomicIntegerFieldUpdater
				.newUpdater(HashedWheelTimeout.class, "state");

		private final HashedWheelTimer timer;
		private final TimerTask task;
		private final long deadline;

		private volatile int state = ST_INIT;

		// remainingRounds will be calculated and set by
		// Worker.transferTimeoutsToBuckets() before the
		// HashedWheelTimeout will be added to the correct HashedWheelBucket.
		long remainingRounds;

		// This will be used to chain timeouts in HashedWheelTimerBucket via a
		// double-linked-list.
		// As only the workerThread will act on it there is no need for
		// synchronization / volatile.
		HashedWheelTimeout next;
		HashedWheelTimeout prev;

		// The bucket to which the timeout was added
		HashedWheelBucket bucket;

		HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
			this.timer = timer;
			this.task = task;
			this.deadline = deadline;
		}

		@SuppressWarnings("unused")
		public HashedWheelTimer timer() {
			return timer;
		}

		public TimerTask task() {
			return task;
		}

		@SuppressWarnings("unused")
		public boolean cancel() {
			// only update the state it will be removed from HashedWheelBucket
			// on next tick.
			if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
				return false;
			}
			// If a task should be canceled we put this to another queue which
			// will be processed on each tick.
			// So this means that we will have a GC latency of max. 1 tick
			// duration which is good enough. This way
			// we can make again use of our MpscLinkedQueue and so minimize the
			// locking / overhead as much as possible.
			timer.cancelledTimeouts.add(this);
			return true;
		}

		void remove() {
			HashedWheelBucket bck = this.bucket;
			if (bck != null) {
				bck.remove(this);
			} else {
				timer.pendingTimeouts.decrementAndGet();
			}
		}

		public boolean compareAndSetState(int expected, int state) {
			return STATE_UPDATER.compareAndSet(this, expected, state);
		}

		public int state() {
			return state;
		}

		public boolean isCancelled() {
			return state() == ST_CANCELLED;
		}

		public boolean isExpired() {
			return state() == ST_EXPIRED;
		}

		public void expire() {
			if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
				return;
			}

			try {
				task.run();
			} catch (Exception t) {
				if (logger.isWarnEnabled()) {
					logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
				}
			}
		}

		@Override
		public String toString() {
			final long currentTime = System.nanoTime();
			long remaining = deadline - currentTime + timer.startTime;

			StringBuilder buf = new StringBuilder(192).append(this.getClass().getSimpleName()).append('(')
					.append("deadline: ");
			if (remaining > 0) {
				buf.append(remaining).append(" ns later");
			} else if (remaining < 0) {
				buf.append(-remaining).append(" ns ago");
			} else {
				buf.append("now");
			}

			if (isCancelled()) {
				buf.append(", cancelled");
			}

			return buf.append(", task: ").append(task()).append(')').toString();
		}
	}

	/**
	 * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list
	 * like datastructure to allow easy removal of HashedWheelTimeouts in the
	 * middle. Also the HashedWheelTimeout act as nodes themself and so no extra
	 * object creation is needed.
	 */
	private static final class HashedWheelBucket {
		// Used for the linked-list datastructure
		private HashedWheelTimeout head;
		private HashedWheelTimeout tail;

		/**
		 * Add {@link HashedWheelTimeout} to this bucket.
		 */
		public void addTimeout(HashedWheelTimeout timeout) {
			assert timeout.bucket == null;
			timeout.bucket = this;
			if (head == null) {
				head = tail = timeout;
			} else {
				tail.next = timeout;
				timeout.prev = tail;
				tail = timeout;
			}
		}

		/**
		 * Expire all {@link HashedWheelTimeout}s for the given
		 * {@code deadline}.
		 */
		public void expireTimeouts(long deadline) {
			HashedWheelTimeout timeout = head;

			// process all timeouts
			while (timeout != null) {
				HashedWheelTimeout next = timeout.next;
				if (timeout.remainingRounds <= 0) {
					next = remove(timeout);
					if (timeout.deadline <= deadline) {
						timeout.expire();
					} else {
						// The timeout was placed into a wrong slot. This should
						// never happen.
						throw new IllegalStateException(
								String.format("timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
					}
				} else if (timeout.isCancelled()) {
					next = remove(timeout);
				} else {
					timeout.remainingRounds--;
				}
				timeout = next;
			}
		}

		public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
			HashedWheelTimeout next = timeout.next;
			// remove timeout that was either processed or cancelled by updating
			// the linked-list
			if (timeout.prev != null) {
				timeout.prev.next = next;
			}
			if (timeout.next != null) {
				timeout.next.prev = timeout.prev;
			}

			if (timeout == head) {
				// if timeout is also the tail we need to adjust the entry too
				if (timeout == tail) {
					tail = null;
					head = null;
				} else {
					head = next;
				}
			} else if (timeout == tail) {
				// if the timeout is the tail modify the tail to be the prev
				// node.
				tail = timeout.prev;
			}
			// null out prev, next and bucket to allow for GC.
			timeout.prev = null;
			timeout.next = null;
			timeout.bucket = null;
			timeout.timer.pendingTimeouts.decrementAndGet();
			return next;
		}

		/**
		 * Clear this bucket and return all not expired / cancelled
		 * {@link Timeout}s.
		 */
		public void clearTimeouts(Set<HashedWheelTimeout> set) {
			for (;;) {
				HashedWheelTimeout timeout = pollTimeout();
				if (timeout == null) {
					return;
				}
				if (timeout.isExpired() || timeout.isCancelled()) {
					continue;
				}
				set.add(timeout);
			}
		}

		private HashedWheelTimeout pollTimeout() {
			HashedWheelTimeout h = this.head;
			if (h == null) {
				return null;
			}
			HashedWheelTimeout next = h.next;
			if (next == null) {
				tail = this.head = null;
			} else {
				this.head = next;
				next.prev = null;
			}

			// null out prev and next to allow for GC.
			h.next = null;
			h.prev = null;
			h.bucket = null;
			return h;
		}
	}

	/********************************************* 使用示例 *********************************************/

	static List<HashedWheelTimer> timers = Lists.newArrayList();

	static AtomicInteger index = new AtomicInteger(0);

	static final ThreadFactory threadFactory = new ThreadFactory() {
		private final AtomicInteger threadNo = new AtomicInteger(1);

		@Override
		public Thread newThread(final Runnable r) {
			final Thread t = new Thread(r);
			t.setName("hashedWheelTimer-" + threadNo.getAndIncrement());
			t.setDaemon(true);
			return t;
		}
	};

	public static void main(String[] args) {
		// 初始化8个hashedWheelTimer
		int size = 8;
		final int mask = size - 1;
		for (int i = 0; i < size; i++) {
			final HashedWheelTimer timer = newMyHashedWheelTimer();
			timers.add(timer);
		}

		// 持续插入新任务
		new Thread(new Runnable() {
			@Override
			public void run() {
				while (index.get() == 0 || index.get() % 800000L != 0) {
					HashedWheelTimer timer = timers.get(index.getAndIncrement() & mask);
					timer.newTimeout(newTimerTask("timer-" + timer.getId() + ":task-" + index.get()),
							RandomHelper.randomInt(12, 10 * 60), TimeUnit.SECONDS);
				}
			}
		}).start();

		// 统计timer
		final HashedWheelTimer reporter = newMyHashedWheelTimer();
		reporter.newTimeout(new TimerTask() {
			@Override
			public void run() {
				for (HashedWheelTimer t : timers) {
					System.out.println(t.getId() + "当前剩余" + t.pendingTimeouts() + "个任务");
				}
				reporter.newTimeout(this, 5L, TimeUnit.SECONDS);
			}
		}, 5L, TimeUnit.SECONDS);

		try {
			Thread.sleep(1000000L);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

	private static TimerTask newTimerTask(final String taskId) {
		return new TimerTask() {
			@Override
			public void run() {
				try {
					Thread.sleep(RandomHelper.randomInt(10, 1000));
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				System.out.println(Thread.currentThread().getName() + ":" + taskId + ":" + DateHelper.now());
			}
		};
	}

	private static HashedWheelTimer newMyHashedWheelTimer() {
		// 每格10秒，一共60个格，即时间轮转一圈理论最少需要10分钟
		return new HashedWheelTimer(threadFactory, 10, TimeUnit.SECONDS, 6 * 10, 100000L);
	}
	
	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

}