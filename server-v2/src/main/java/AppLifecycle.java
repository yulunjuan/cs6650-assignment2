import com.rabbitmq.client.Channel;
import mq.ChannelPool;
import mq.RabbitConfig;
import mq.SwipePublisher;
import mq.Topology;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.net.InetAddress;

// Opens RabbitMQ resources when the webapp starts and closes them on shutdown
public class AppLifecycle implements ServletContextListener {
  public static final String PUBLISHER_ATTR = "swipePublisher";
  public static final String SERVER_ID = RabbitConfig.get("SERVER_ID", hostName());

  private ChannelPool pool;
  private BackplaneListener backplane;

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    try {
      int poolSize = Integer.parseInt(RabbitConfig.get("CHANNEL_POOL_SIZE", "64"));
      pool = new ChannelPool(RabbitConfig.newFactory(false), poolSize);
      pool.init();

      Channel ch = pool.borrowChannel();
      try {
        Topology.declare(ch);
      } finally {
        pool.returnChannel(ch);
      }
      sce.getServletContext().setAttribute(PUBLISHER_ATTR, new SwipePublisher(pool));

      backplane = new BackplaneListener();
      backplane.start(RabbitConfig.newFactory(true));
      System.out.println("server-v2 " + SERVER_ID + " connected to RabbitMQ, channel pool size " + poolSize);
    } catch (Exception e) {
      // Fail the deployment: /health then returns 404 and the ALB marks this target unhealthy
      throw new RuntimeException("Cannot connect to RabbitMQ", e);
    }
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    if (backplane != null) {
      backplane.close();
    }
    if (pool != null) {
      pool.close();
    }
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
