package mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// One shared Connection, a fixed number of channel slots. A borrowed channel is used by
// exactly one thread until it is returned. Dead channels are replaced lazily on borrow,
// and a dead connection is re-opened on demand, so the pool heals itself after a drop.
public class ChannelPool {
  private static final long BORROW_TIMEOUT_MS = 5_000;
  private static final long RECONNECT_BACKOFF_MS = 1_000;

  private final ConnectionFactory factory;
  private final int size;
  private final BlockingQueue<Channel> pool;

  private Connection connection;            // guarded by this
  private long lastConnectFailureMs;        // guarded by this

  public ChannelPool(ConnectionFactory factory, int size) {
    this.factory = factory;
    this.size = size;
    this.pool = new ArrayBlockingQueue<>(size);
  }

  public void init() throws IOException, TimeoutException {
    for (int i = 0; i < size; i++) {
      pool.add(newChannel());
    }
  }

  public Channel borrowChannel() throws IOException, TimeoutException, InterruptedException {
    Channel ch = pool.poll(BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    if (ch == null) {
      throw new TimeoutException("No channel available within " + BORROW_TIMEOUT_MS + " ms");
    }
    if (ch.isOpen()) {
      return ch;
    }
    try {
      return newChannel();
    } catch (IOException | TimeoutException | RuntimeException e) {
      // Put the dead channel back so the pool keeps its size; it is retried on the next borrow
      pool.offer(ch);
      throw e;
    }
  }

  public void returnChannel(Channel ch) {
    // Closed channels are returned too; borrowChannel() replaces them
    pool.offer(ch);
  }

  private Channel newChannel() throws IOException, TimeoutException {
    Channel ch = getConnection().createChannel();
    ch.confirmSelect();
    return ch;
  }

  private synchronized Connection getConnection() throws IOException, TimeoutException {
    if (connection != null && connection.isOpen()) {
      return connection;
    }
    // Fail fast while the broker is down so request threads do not pile up on connect attempts
    long now = System.currentTimeMillis();
    if (now - lastConnectFailureMs < RECONNECT_BACKOFF_MS) {
      throw new IOException("Broker unavailable, backing off before reconnect");
    }
    try {
      if (connection != null) {
        System.out.println("RabbitMQ connection lost, reconnecting");
      }
      connection = factory.newConnection("server-v2-publisher");
      return connection;
    } catch (IOException | TimeoutException e) {
      lastConnectFailureMs = now;
      throw e;
    }
  }

  public synchronized void close() {
    try {
      if (connection != null && connection.isOpen()) {
        connection.close();  // also closes every channel in the pool
      }
    } catch (IOException e) {
      System.out.println("Error closing RabbitMQ connection: " + e.getMessage());
    }
  }
}
