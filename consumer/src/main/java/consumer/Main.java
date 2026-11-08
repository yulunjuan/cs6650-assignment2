package consumer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Main {
  public static void main(String[] args) throws Exception {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(Config.RABBIT_HOST);
    factory.setPort(Config.RABBIT_PORT);
    factory.setUsername(Config.RABBIT_USER);
    factory.setPassword(Config.RABBIT_PASS);
    // Library re-opens the connection and re-subscribes every consumer after a drop;
    // unacked messages are redelivered and deduped by eventId
    factory.setAutomaticRecoveryEnabled(true);

    // Consumer threads: the client library runs every channel's deliveries on this pool,
    // never more than one thread per channel at a time
    ThreadPoolExecutor workers = new ThreadPoolExecutor(Config.MIN_THREADS, Config.MIN_THREADS,
        60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    Connection connection = factory.newConnection(workers, "consumer");

    Channel setup = connection.createChannel();
    Topology.declare(setup);

    Aggregator aggregator = new Aggregator();
    LoadMonitor monitor = new LoadMonitor(setup, workers, aggregator);

    // One channel + one consumer per resort queue keeps each resort's swipes in order
    for (int r = 1; r <= Topology.NUM_RESORTS; r++) {
      Channel ch = connection.createChannel();
      ch.basicQos(Config.PREFETCH);
      ch.basicConsume(Topology.queueName(r), false, new SwipeConsumer(ch, aggregator, monitor));
    }

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.scheduleAtFixedRate(monitor, Config.MONITOR_INTERVAL_SEC, Config.MONITOR_INTERVAL_SEC,
        TimeUnit.SECONDS);

    HealthServer health = new HealthServer();
    health.start(connection, aggregator, monitor);

    System.out.printf("Consumer started: rabbit=%s threads=%d..%d prefetch=%d health=:%d%n",
        Config.RABBIT_HOST, Config.MIN_THREADS, Config.MAX_THREADS, Config.PREFETCH, Config.HEALTH_PORT);

    // Graceful shutdown (Ctrl+C / systemctl stop): closing the connection cancels the
    // consumers and returns unacked messages to their queues
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("Shutting down consumer");
      scheduler.shutdownNow();
      health.stop();
      try {
        connection.close();
      } catch (Exception e) {
        System.out.println("Error closing connection: " + e.getMessage());
      }
      workers.shutdown();
    }));
  }
}
