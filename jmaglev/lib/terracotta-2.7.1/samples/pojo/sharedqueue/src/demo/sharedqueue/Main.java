/**
 *
 * All content copyright (c) 2003-2008 Terracotta, Inc.,
 * except as may otherwise be noted in a separate copyright notice.
 * All rights reserved.
 *
 */
package demo.sharedqueue;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Handler;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.bio.SocketConnector;
import org.mortbay.jetty.handler.ContextHandler;
import org.mortbay.jetty.handler.HandlerCollection;
import org.mortbay.jetty.handler.ResourceHandler;

/**
 *  Description of the Class
 *
 *@author    Terracotta, Inc.
 */
public class Main {
   private final File cwd = new File(System.getProperty("user.dir"));

   private int lastPortUsed;

   private demo.sharedqueue.Queue queue;

   private Worker worker;

   public final void start(int port) throws Exception {
      String nodeId = registerForNotifications();
      port = setPort(port);

      System.out.println("DSO SharedQueue (node " + nodeId + ")");
      System.out.println("Open your browser and go to - http://"
            + getHostName() + ":" + port + "/webapp\n");

      Server server = new Server();
      Connector connector = new SocketConnector();
      connector.setPort(port);
      server.setConnectors(new Connector[]{connector});

      queue = new Queue(port);
      worker = queue.createWorker(nodeId);

      ResourceHandler resourceHandler = new ResourceHandler();
      resourceHandler.setResourceBase(".");

      ContextHandler ajaxContext = new ContextHandler();
      ajaxContext.setContextPath(SimpleHttpHandler.ACTION);
      ajaxContext.setResourceBase(cwd.getPath());
      ajaxContext.setClassLoader(Thread.currentThread()
            .getContextClassLoader());
      ajaxContext.addHandler(new SimpleHttpHandler(queue));

      HandlerCollection handlers = new HandlerCollection();
      handlers.setHandlers(new Handler[]{ajaxContext, resourceHandler});
      server.setHandler(handlers);

      startReaper();
      server.start();
      server.join();
   }

   private final int setPort(int port) {
      if (port == -1) {
         if (lastPortUsed == 0) {
            port = lastPortUsed = 1990;
         }
         else {
            port = ++lastPortUsed;
         }
      }
      else {
         lastPortUsed = port;
      }
      return port;
   }

   /**
    *  Starts a thread to identify dead workers (From nodes that have been
    *  brought down) and removes them from the (shared) list of workers.
    */
   private final void startReaper() {
      Thread reaper = new Thread(
               new Runnable() {
                  public void run() {
                     while (true) {
                        Main.this.queue.reap();
                        try {
                           Thread.sleep(1000);
                        }
                        catch (InterruptedException ie) {
                           System.err.println(ie.getMessage());
                        }
                     }
                  }
               });
      reaper.start();
   }

   /**
    *  Registers this client for JMX notifications.
    *
    *@return                Description of the Returned Value
    *@exception  Exception  Description of Exception
    *@returns               This clients Node ID
    */
   private final String registerForNotifications() throws Exception {
      java.util.List servers = MBeanServerFactory.findMBeanServer(null);
      if (servers.size() == 0) {

         System.err.println("WARNING: No JMX servers found, unable to register for notifications.");
         return "0";
      }

      MBeanServer server = (MBeanServer) servers.get(0);
      final ObjectName clusterBean = new ObjectName(
            "org.terracotta:type=Terracotta Cluster,name=Terracotta Cluster Bean");
      ObjectName delegateName =
            ObjectName.getInstance("JMImplementation:type=MBeanServerDelegate");
      final java.util.List clusterBeanBag = new java.util.ArrayList();

      // listener for newly registered MBeans
      NotificationListener listener0 =
         new NotificationListener() {
            public void handleNotification(Notification notification,
                  Object handback) {
               synchronized (clusterBeanBag) {
                  clusterBeanBag.add(handback);
                  clusterBeanBag.notifyAll();
               }
            }
         };

      // filter to let only clusterBean passed through
      NotificationFilter filter0 =
         new NotificationFilter() {
            public boolean isNotificationEnabled(Notification notification) {
               if (notification.getType().equals("JMX.mbean.registered")
                     && ((MBeanServerNotification) notification)
                     .getMBeanName().equals(clusterBean)) {
                  return true;
               }
               return false;
            }
         };

      // add our listener for clusterBean's registration
      server.addNotificationListener(delegateName, listener0, filter0,
            clusterBean);

      // because of race condition, clusterBean might already have registered
      // before we registered the listener
      java.util.Set allObjectNames = server.queryNames(null, null);

      if (!allObjectNames.contains(clusterBean)) {
         synchronized (clusterBeanBag) {
            while (clusterBeanBag.isEmpty()) {
               clusterBeanBag.wait();
            }
         }
      }

      // clusterBean is now registered, no need to listen for it
      server.removeNotificationListener(delegateName, listener0);

      // listener for clustered bean events
      NotificationListener listener1 =
         new NotificationListener() {
            public void handleNotification(Notification notification,
                  Object handback) {
               String nodeId = notification.getMessage();
               Worker worker = Main.this.queue.getWorker(nodeId);
               if (worker != null) {
                  worker.markForExpiration();
               }
               else {
                  System.err.println("Worker for nodeId: " + nodeId
                        + " not found.");
               }
            }
         };

      // filter for nodeDisconnected notifications only
      NotificationFilter filter1 =
         new NotificationFilter() {
            public boolean isNotificationEnabled(Notification notification) {
               return notification.getType().equals(
                     "com.tc.cluster.event.nodeDisconnected");
            }
         };

      // now that we have the clusterBean, add listener for membership events
      server.addNotificationListener(clusterBean, listener1, filter1,
            clusterBean);
      return (server.getAttribute(clusterBean, "NodeId")).toString();
   }

   public static final void main(String[] args) throws Exception {
      int port = -1;
      try {
         port = Integer.parseInt(args[0]);
      }
      catch (Exception e) {
      }
      (new Main()).start(port);
   }

   static final String getHostName() {
      try {
         InetAddress addr = InetAddress.getLocalHost();
         return addr.getHostName();
      }
      catch (UnknownHostException e) {
         return "Unknown";
      }
   }
}
