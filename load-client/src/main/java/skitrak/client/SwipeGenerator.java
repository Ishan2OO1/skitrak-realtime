package skitrak.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Producer thread: generates every swipe for the run and feeds them into the queue.
 */
public class SwipeGenerator implements Runnable {

    private static final int MAX_SKIER_ID = 100_000;
    private static final int MAX_LIFT_ID = 40;
    private static final int MAX_TIME = 360;

    private final BlockingQueue<SwipeEvent> queue;
    private final int total;
    private final int resortCount;
    private final int seasonID;
    private final int dayID;

    public SwipeGenerator(BlockingQueue<SwipeEvent> queue, int total,
                          int resortCount, int seasonID, int dayID) {
        this.queue = queue;
        this.total = total;
        this.resortCount = resortCount;
        this.seasonID = seasonID;
        this.dayID = dayID;
    }

    @Override
    public void run() {
        // has to be fetched here so it belongs to the generator thread
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        try {
            for (int i = 0; i < total; i++) {
                SwipeEvent swipe = new SwipeEvent(
                        rnd.nextInt(1, resortCount + 1),   // resortID
                        seasonID,
                        dayID,
                        rnd.nextInt(1, MAX_SKIER_ID + 1),  // skierID
                        rnd.nextInt(1, MAX_TIME + 1),      // time
                        rnd.nextInt(1, MAX_LIFT_ID + 1));  // liftID
                queue.put(swipe);   // waits when the queue is full, so senders never wait on us
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}