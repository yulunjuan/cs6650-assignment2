package client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

public class SwipeGenerator implements Runnable {
    private final BlockingQueue<SwipeEvent> queue;
    private final int total;

    public SwipeGenerator(BlockingQueue<SwipeEvent> queue, int total) {
        this.queue = queue;
        this.total = total;
    }

    @Override
    public void run() {
        for (int i = 0; i < total; i++) {
            // Unbounded queue: offer() never blocks, so generation stays far ahead of the senders
            queue.offer(randomSwipe());
        }
    }

    public static SwipeEvent randomSwipe() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new SwipeEvent(
                r.nextInt(1, 100_001),               // skierID
                r.nextInt(1, Config.NUM_RESORTS + 1), // resortID
                r.nextInt(1, 41),                    // liftID
                Config.SEASON_ID,
                Config.DAY_ID,
                r.nextInt(1, 361));                  // time
    }
}