/*
 * Copyright 2009 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.core.server.impl;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.channels.ClosedChannelException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServer;

import org.hornetq.api.core.AlreadyReplicatingException;
import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.HornetQExceptionType;
import org.hornetq.api.core.InternalErrorException;
import org.hornetq.api.core.Pair;
import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.core.client.impl.ClientSessionFactoryImpl;
import org.hornetq.core.client.impl.ClientSessionFactoryInternal;
import org.hornetq.core.client.impl.ServerLocatorInternal;
import org.hornetq.core.config.BridgeConfiguration;
import org.hornetq.core.config.Configuration;
import org.hornetq.core.config.CoreQueueConfiguration;
import org.hornetq.core.config.DivertConfiguration;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.deployers.Deployer;
import org.hornetq.core.deployers.DeploymentManager;
import org.hornetq.core.deployers.impl.AddressSettingsDeployer;
import org.hornetq.core.deployers.impl.BasicUserCredentialsDeployer;
import org.hornetq.core.deployers.impl.FileDeploymentManager;
import org.hornetq.core.deployers.impl.QueueDeployer;
import org.hornetq.core.deployers.impl.SecurityDeployer;
import org.hornetq.core.filter.Filter;
import org.hornetq.core.filter.impl.FilterImpl;
import org.hornetq.core.journal.IOCriticalErrorListener;
import org.hornetq.core.journal.JournalLoadInformation;
import org.hornetq.core.journal.SequentialFile;
import org.hornetq.core.journal.impl.SyncSpeedTest;
import org.hornetq.core.management.impl.HornetQServerControlImpl;
import org.hornetq.core.paging.PagingManager;
import org.hornetq.core.paging.cursor.PageSubscription;
import org.hornetq.core.paging.impl.PagingManagerImpl;
import org.hornetq.core.paging.impl.PagingStoreFactoryNIO;
import org.hornetq.core.persistence.GroupingInfo;
import org.hornetq.core.persistence.QueueBindingInfo;
import org.hornetq.core.persistence.StorageManager;
import org.hornetq.core.persistence.config.PersistedAddressSetting;
import org.hornetq.core.persistence.config.PersistedRoles;
import org.hornetq.core.persistence.impl.journal.JournalStorageManager;
import org.hornetq.core.persistence.impl.journal.OperationContextImpl;
import org.hornetq.core.persistence.impl.nullpm.NullStorageManager;
import org.hornetq.core.postoffice.Binding;
import org.hornetq.core.postoffice.DuplicateIDCache;
import org.hornetq.core.postoffice.PostOffice;
import org.hornetq.core.postoffice.QueueBinding;
import org.hornetq.core.postoffice.impl.DivertBinding;
import org.hornetq.core.postoffice.impl.LocalQueueBinding;
import org.hornetq.core.postoffice.impl.PostOfficeImpl;
import org.hornetq.core.protocol.core.Channel;
import org.hornetq.core.protocol.core.CoreRemotingConnection;
import org.hornetq.core.protocol.core.impl.ChannelImpl.CHANNEL_ID;
import org.hornetq.core.remoting.CloseListener;
import org.hornetq.core.remoting.FailureListener;
import org.hornetq.core.remoting.server.RemotingService;
import org.hornetq.core.remoting.server.impl.RemotingServiceImpl;
import org.hornetq.core.replication.ReplicationEndpoint;
import org.hornetq.core.replication.ReplicationManager;
import org.hornetq.core.security.CheckType;
import org.hornetq.core.security.Role;
import org.hornetq.core.security.SecurityStore;
import org.hornetq.core.security.impl.SecurityStoreImpl;
import org.hornetq.core.server.ActivateCallback;
import org.hornetq.core.server.Bindable;
import org.hornetq.core.server.Divert;
import org.hornetq.core.server.HornetQComponent;
import org.hornetq.core.server.HornetQLogger;
import org.hornetq.core.server.HornetQMessageBundle;
import org.hornetq.core.server.HornetQServer;
import org.hornetq.core.server.JournalType;
import org.hornetq.core.server.LargeServerMessage;
import org.hornetq.core.server.MemoryManager;
import org.hornetq.core.server.NodeManager;
import org.hornetq.core.server.Queue;
import org.hornetq.core.server.QueueFactory;
import org.hornetq.core.server.ServerSession;
import org.hornetq.core.server.cluster.ClusterConnection;
import org.hornetq.core.server.cluster.ClusterManager;
import org.hornetq.core.server.cluster.Transformer;
import org.hornetq.core.server.cluster.impl.ClusterManagerImpl;
import org.hornetq.core.server.group.GroupingHandler;
import org.hornetq.core.server.group.impl.GroupBinding;
import org.hornetq.core.server.group.impl.GroupingHandlerConfiguration;
import org.hornetq.core.server.group.impl.LocalGroupingHandler;
import org.hornetq.core.server.group.impl.RemoteGroupingHandler;
import org.hornetq.core.server.impl.QuorumManager.BACKUP_ACTIVATION;
import org.hornetq.core.server.management.ManagementService;
import org.hornetq.core.server.management.impl.ManagementServiceImpl;
import org.hornetq.core.settings.HierarchicalRepository;
import org.hornetq.core.settings.impl.AddressSettings;
import org.hornetq.core.settings.impl.HierarchicalObjectRepository;
import org.hornetq.core.transaction.ResourceManager;
import org.hornetq.core.transaction.impl.ResourceManagerImpl;
import org.hornetq.core.version.Version;
import org.hornetq.spi.core.protocol.RemotingConnection;
import org.hornetq.spi.core.protocol.SessionCallback;
import org.hornetq.spi.core.security.HornetQSecurityManager;
import org.hornetq.utils.ClassloadingUtil;
import org.hornetq.utils.ExecutorFactory;
import org.hornetq.utils.HornetQThreadFactory;
import org.hornetq.utils.OrderedExecutorFactory;
import org.hornetq.utils.SecurityFormatter;
import org.hornetq.utils.VersionLoader;

/**
 * The HornetQ server implementation
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ataylor@redhat.com>Andy Taylor</a>
 * @version <tt>$Revision: 3543 $</tt> <p/> $Id: ServerPeer.java 3543 2008-01-07 22:31:58Z clebert.suconic@jboss.com $
 */
public class HornetQServerImpl implements HornetQServer
{
   /**
    * JMS Topics (which are outside of the scope of the core API) will require a dumb subscription
    * with a dummy-filter at this current version as a way to keep its existence valid and TCK
    * tests. That subscription needs an invalid filter, however paging needs to ignore any
    * subscription with this filter. For that reason, this filter needs to be rejected on paging or
    * any other component on the system, and just be ignored for any purpose It's declared here as
    * this filter is considered a global ignore
    */
   public static final String GENERIC_IGNORED_FILTER = "__HQX=-1";

   enum SERVER_STATE
   {
      /** start() has been called but components are not initialized */
      INITIALIZING,
      /**
       * server is started. {@code server.isStarted()} returns {@code true}, and all assumptions
       * about it hold.
       */
      STARTED,
      /**
       * Stopped. Either stop() has been called and has finished running, or start() has never been
       * called.
       */
      STOPPED;
   }
   private volatile SERVER_STATE state = SERVER_STATE.STOPPED;

   private final Version version;

   private final HornetQSecurityManager securityManager;

   private final Configuration configuration;

   private final MBeanServer mbeanServer;

   private volatile SecurityStore securityStore;

   private final HierarchicalRepository<AddressSettings> addressSettingsRepository;

   private volatile QueueFactory queueFactory;

   private volatile PagingManager pagingManager;

   private volatile PostOffice postOffice;

   private volatile ExecutorService threadPool;

   private volatile ScheduledExecutorService scheduledPool;

   private volatile ExecutorFactory executorFactory;

   private final HierarchicalRepository<Set<Role>> securityRepository;

   private volatile ResourceManager resourceManager;

   private volatile HornetQServerControlImpl messagingServerControl;

   private volatile ClusterManager clusterManager;

   private volatile StorageManager storageManager;

   private volatile RemotingService remotingService;

   private volatile ManagementService managementService;

   private volatile ConnectorsService connectorsService;

   private MemoryManager memoryManager;

   private volatile DeploymentManager deploymentManager;

   private Deployer basicUserCredentialsDeployer;

   private Deployer addressSettingsDeployer;

   private Deployer queueDeployer;

   private Deployer securityDeployer;

   private final Map<String, ServerSession> sessions = new ConcurrentHashMap<String, ServerSession>();

   /**
    * We guard the {@code initialised} field because if we restart a {@code HornetQServer}, we need
    * to replace the {@code CountDownLatch} by a new one.
    */
   private final Object initialiseLock = new Object();
   private CountDownLatch initialised = new CountDownLatch(1);

   private final Object startUpLock = new Object();
   private final Object replicationLock = new Object();

   /**
    * Only applicable to 'remote backup servers'. If this flag is false the backup may not become
    * 'live'.
    */
   private volatile boolean backupUpToDate = true;

   private ReplicationManager replicationManager;

   private ReplicationEndpoint replicationEndpoint;

   private final Set<ActivateCallback> activateCallbacks = new HashSet<ActivateCallback>();

   private volatile GroupingHandler groupingHandler;

   private NodeManager nodeManager;

   // Used to identify the server on tests... useful on debugging testcases
   private String identity;

   private Thread backupActivationThread;

   private Activation activation;

   private final ShutdownOnCriticalErrorListener shutdownOnCriticalIO = new ShutdownOnCriticalErrorListener();

   // Constructors
   // ---------------------------------------------------------------------------------

   public HornetQServerImpl()
   {
      this(null, null, null);
   }

   public HornetQServerImpl(final Configuration configuration)
   {
      this(configuration, null, null);
   }

   public HornetQServerImpl(final Configuration configuration, final MBeanServer mbeanServer)
   {
      this(configuration, mbeanServer, null);
   }

   public HornetQServerImpl(final Configuration configuration, final HornetQSecurityManager securityManager)
   {
      this(configuration, null, securityManager);
   }

   public HornetQServerImpl(Configuration configuration,
                            MBeanServer mbeanServer,
                            final HornetQSecurityManager securityManager)
   {
      if (configuration == null)
      {
         configuration = new ConfigurationImpl();
      }

      if (mbeanServer == null)
      {
         // Just use JVM mbean server
         mbeanServer = ManagementFactory.getPlatformMBeanServer();
      }

      // We need to hard code the version information into a source file

      version = VersionLoader.getVersion();

      this.configuration = configuration;

      this.mbeanServer = mbeanServer;

      this.securityManager = securityManager;

      addressSettingsRepository = new HierarchicalObjectRepository<AddressSettings>();

      addressSettingsRepository.setDefault(new AddressSettings());

      securityRepository = new HierarchicalObjectRepository<Set<Role>>();

      securityRepository.setDefault(new HashSet<Role>());

   }

   // life-cycle methods
   // ----------------------------------------------------------------

   /*
    * Can be overridden for tests
    */
   protected NodeManager createNodeManager(final String directory)
   {
      if (configuration.getJournalType() == JournalType.ASYNCIO && AsynchronousFileImpl.isLoaded())
      {
         return new AIOFileLockNodeManager(directory);
      }
      else
      {
         return new FileLockNodeManager(directory);
      }
   }

   public synchronized void start() throws Exception
   {
      if (state != SERVER_STATE.STOPPED)
      {
         HornetQLogger.LOGGER.debug("Server already started!");
         return;
      }
      state = SERVER_STATE.INITIALIZING;

      HornetQLogger.LOGGER.debug("Starting server " + this);
      OperationContextImpl.clearContext();

      try
      {

         checkJournalDirectory();

         nodeManager = createNodeManager(configuration.getJournalDirectory());

         nodeManager.start();

         HornetQLogger.LOGGER.serverStarting((configuration.isBackup() ? "backup" : "live"),  configuration);

         if (configuration.isRunSyncSpeedTest())
         {
            SyncSpeedTest test = new SyncSpeedTest();

            test.run();
         }

         if (!configuration.isBackup())
         {
            if (configuration.isSharedStore() && configuration.isPersistenceEnabled())
            {
               activation = new SharedStoreLiveActivation();
            }
            else
            {
               activation = new SharedNothingLiveActivation();
            }

            activation.run();
            state = SERVER_STATE.STARTED;
            HornetQLogger.LOGGER.serverStarted(getVersion().getFullVersion(), nodeManager.getNodeId(),
                                               identity != null ? identity : "");
         }
         // The activation on fail-back may change the value of isBackup, for that reason we are not
         // using else here
         if (configuration.isBackup())
         {
             if (configuration.isSharedStore())
             {
                activation = new SharedStoreBackupActivation();
             }
             else
             {
                assert replicationEndpoint == null;
                backupUpToDate = false;
                replicationEndpoint = new ReplicationEndpoint(this, shutdownOnCriticalIO);
                activation = new SharedNothingBackupActivation();
             }

             backupActivationThread = new Thread(activation, HornetQMessageBundle.BUNDLE.activationForServer(this));
             backupActivationThread.start();
         }
         // start connector service
         connectorsService = new ConnectorsService(configuration, storageManager, scheduledPool, postOffice);
         connectorsService.start();
      }
      finally
      {
         // this avoids embedded applications using dirty contexts from startup
         OperationContextImpl.clearContext();
      }
   }

   @Override
   protected void finalize() throws Throwable
   {
      if (state != SERVER_STATE.STOPPED)
      {
         HornetQLogger.LOGGER.serverFinalisedWIthoutBeingSTopped();

         stop();
      }

      super.finalize();
   }

   public void stop() throws Exception
   {
      stop(configuration.isFailoverOnServerShutdown());
   }

   public void threadDump(final String reason)
   {
      StringWriter str = new StringWriter();
      PrintWriter out = new PrintWriter(str);

      Map<Thread, StackTraceElement[]> stackTrace = Thread.getAllStackTraces();

      out.println(HornetQMessageBundle.BUNDLE.generatingThreadDump(reason));
      out.println("*******************************************************************************");

      for (Map.Entry<Thread, StackTraceElement[]> el : stackTrace.entrySet())
      {
         out.println("===============================================================================");
         out.println(HornetQMessageBundle.BUNDLE.threadDump(el.getKey(), el.getKey().getName(), el.getKey().getId(), el.getKey().getThreadGroup()));
         out.println();
         for (StackTraceElement traceEl : el.getValue())
         {
            out.println(traceEl);
         }
      }

      out.println("===============================================================================");
      out.println(HornetQMessageBundle.BUNDLE.endThreadDump());
      out.println("*******************************************************************************");

      HornetQLogger.LOGGER.warn(str.toString());
   }

   public void stop(boolean failoverOnServerShutdown) throws Exception
   {
      stop(failoverOnServerShutdown, false);
   }

   private void stop(boolean failoverOnServerShutdown, boolean criticalIOError) throws Exception
   {
      synchronized (this)
      {
         if (state == SERVER_STATE.STOPPED)
         {
            return;
         }

         if (replicationManager!=null) {
            remotingService.freeze(replicationManager.getBackupTransportConnection());
            final ReplicationManager localReplicationManager = replicationManager;
            threadPool.execute(new Runnable() {
               @Override
               public void run()
               {
                  try
                  {
                     Thread.sleep(10000);
                     localReplicationManager.clearReplicationTokens();
                  }
                  catch (InterruptedException e)
                  {
                     // no-op ignore and proceed.
                  }
               }
            });
            stopComponent(pagingManager);
            replicationManager.sendLiveIsStopping();
            stopComponent(replicationManager);
         }

         stopComponent(connectorsService);

         // we stop the groupingHandler before we stop the cluster manager so binding mappings
         // aren't removed in case of failover
         if (groupingHandler != null)
         {
            managementService.removeNotificationListener(groupingHandler);
            groupingHandler = null;
         }
         stopComponent(clusterManager);
      }


      // We stop remotingService before otherwise we may lock the system in case of a critical IO
      // error shutdown
      remotingService.stop(criticalIOError);

      // We close all the exception in an attempt to let any pending IO to finish
      // to avoid scenarios where the send or ACK got to disk but the response didn't get to the client
      // It may still be possible to have this scenario on a real failure (without the use of XA)
      // But at least we will do our best to avoid it on regular shutdowns
      for (ServerSession session : sessions.values())
      {
         try
         {
            storageManager.setContext(session.getSessionContext());
            session.close(true);
            if (!criticalIOError)
            {
               session.waitContextCompletion();
            }
         }
         catch (Exception e)
         {
            // If anything went wrong with closing sessions.. we should ignore it
            // such as transactions.. etc.
            HornetQLogger.LOGGER.errorClosingSessionsWhileStoppingServer(e);
         }
      }

      storageManager.clearContext();

      synchronized (this)
      {
         synchronized (startUpLock)
         {

         // Stop the deployers
         if (configuration.isFileDeploymentEnabled())
         {
               stopComponent(basicUserCredentialsDeployer);
               stopComponent(addressSettingsDeployer);
               stopComponent(queueDeployer);
               stopComponent(securityDeployer);
               stopComponent(deploymentManager);
         }

            managementService.unregisterServer();

            stopComponent(managementService);
            stopComponent(replicationManager);
            stopComponent(pagingManager);
            stopComponent(replicationEndpoint);

            if (!criticalIOError)
            {
               stopComponent(storageManager);
            }
            stopComponent(securityManager);
            stopComponent(resourceManager);

            stopComponent(postOffice);

            if (scheduledPool != null)
            {
               // we just interrupt all running tasks, these are supposed to be pings and the like.
               scheduledPool.shutdownNow();
            }

            stopComponent(memoryManager);

            if (threadPool != null)
            {
               threadPool.shutdown();
               try
               {
                  if (!threadPool.awaitTermination(10, TimeUnit.SECONDS))
                  {
                     HornetQLogger.LOGGER.timedOutStoppingThreadpool(threadPool);
                     for (Runnable r : threadPool.shutdownNow())
                     {
                        HornetQLogger.LOGGER.debug("Cancelled the execution of " + r);
                     }
                  }
               }
               catch (InterruptedException e)
               {
                  // Ignore
               }
            }

            scheduledPool = null;
            threadPool = null;

            if (securityStore != null)
           securityStore.stop();

         threadPool = null;

         scheduledPool = null;

         pagingManager = null;
         securityStore = null;
         resourceManager = null;
            replicationManager = null;
            replicationEndpoint = null;
         postOffice = null;
         queueFactory = null;
         resourceManager = null;
         messagingServerControl = null;
         memoryManager = null;

         sessions.clear();

            state = SERVER_STATE.STOPPED;
            synchronized (initialiseLock)
            {
               // replace the latch only if necessary. It could still be '1' in case of errors
               // during start-up.
               if (initialised.getCount() < 1)
                  initialised = new CountDownLatch(1);
            }
         }

         // to display in the log message
         SimpleString tempNodeID = getNodeID();
         if (activation != null)
         {
            activation.close(failoverOnServerShutdown);
         }
         if (backupActivationThread != null)
         {

            backupActivationThread.join(30000);
            if (backupActivationThread.isAlive())
            {
               HornetQLogger.LOGGER.backupActivationDidntFinish(this);
               backupActivationThread.interrupt();
            }
         }

         stopComponent(nodeManager);

         nodeManager = null;

         addressSettingsRepository.clearListeners();

         addressSettingsRepository.clearCache();

         HornetQLogger.LOGGER.serverStopped(getVersion().getFullVersion(), tempNodeID);
      }

   }

   private static void stopComponent(HornetQComponent component) throws Exception
   {
      if (component != null)
         component.stop();
   }

   // HornetQServer implementation
   // -----------------------------------------------------------

   public String describe()
   {
      StringWriter str = new StringWriter();
      PrintWriter out = new PrintWriter(str);

      out.println(HornetQMessageBundle.BUNDLE.serverDescribe(identity, getClusterManager().describe()));

      return str.toString();
   }

   public void setIdentity(String identity)
   {
      this.identity = identity;
   }

   public String getIdentity()
   {
      return identity;
   }

   public ScheduledExecutorService getScheduledPool()
   {
      return scheduledPool;
   }

   public Configuration getConfiguration()
   {
      return configuration;
   }

   public PagingManager getPagingManager()
   {
      return pagingManager;
   }

   public RemotingService getRemotingService()
   {
      return remotingService;
   }

   public StorageManager getStorageManager()
   {
      return storageManager;
   }

   public HornetQSecurityManager getSecurityManager()
   {
      return securityManager;
   }

   public ManagementService getManagementService()
   {
      return managementService;
   }

   public HierarchicalRepository<Set<Role>> getSecurityRepository()
   {
      return securityRepository;
   }

   public NodeManager getNodeManager()
   {
      return nodeManager;
   }

   public HierarchicalRepository<AddressSettings> getAddressSettingsRepository()
   {
      return addressSettingsRepository;
   }

   public DeploymentManager getDeploymentManager()
   {
      return deploymentManager;
   }

   public ResourceManager getResourceManager()
   {
      return resourceManager;
   }

   public Version getVersion()
   {
      return version;
   }

   public boolean isStarted()
   {
      return state == SERVER_STATE.STARTED;
   }

   public ClusterManager getClusterManager()
   {
      return clusterManager;
   }

   public ServerSession createSession(final String name,
                                      final String username,
                                      final String password,
                                      final int minLargeMessageSize,
                                      final RemotingConnection connection,
                                      final boolean autoCommitSends,
                                      final boolean autoCommitAcks,
                                      final boolean preAcknowledge,
                                      final boolean xa,
                                      final String defaultAddress,
                                      final SessionCallback callback) throws Exception
   {

      if (securityStore != null)
      {
         securityStore.authenticate(username, password);
      }

      final ServerSessionImpl session = new ServerSessionImpl(name,
                                                              username,
                                                              password,
                                                              minLargeMessageSize,
                                                              autoCommitSends,
                                                              autoCommitAcks,
                                                              preAcknowledge,
                                                              configuration.isPersistDeliveryCountBeforeDelivery(),
                                                              xa,
                                                              connection,
                                                              storageManager,
                                                              postOffice,
                                                              resourceManager,
                                                              securityStore,
                                                              managementService,
                                                              this,
                                                              configuration.getManagementAddress(),
                                                              defaultAddress == null ? null
                                                                                    : new SimpleString(defaultAddress),
                                                              callback);

      sessions.put(name, session);

      return session;
   }

   private synchronized ReplicationEndpoint connectToReplicationEndpoint(final Channel channel) throws Exception
   {
      if (!configuration.isBackup())
      {
         throw HornetQMessageBundle.BUNDLE.serverNotBackupServer();
      }

      channel.setHandler(replicationEndpoint);

      if (replicationEndpoint.getChannel() != null)
      {
         throw HornetQMessageBundle.BUNDLE.alreadyHaveReplicationServer();
      }

      replicationEndpoint.setChannel(channel);

      return replicationEndpoint;
   }

   public void removeSession(final String name) throws Exception
   {
      sessions.remove(name);
   }

   public boolean lookupSession(String key, String value)
   {
      // getSessions is called here in a try to minimize locking the Server while this check is being done
      Set<ServerSession> allSessions = getSessions();

      for (ServerSession session : allSessions)
      {
         String metaValue = session.getMetaData(key);
         if (metaValue != null && metaValue.equals(value))
         {
            return true;
         }
      }

      return false;
   }

   public synchronized List<ServerSession> getSessions(final String connectionID)
   {
      Set<Entry<String, ServerSession>> sessionEntries = sessions.entrySet();
      List<ServerSession> matchingSessions = new ArrayList<ServerSession>();
      for (Entry<String, ServerSession> sessionEntry : sessionEntries)
      {
         ServerSession serverSession = sessionEntry.getValue();
         if (serverSession.getConnectionID().toString().equals(connectionID))
         {
            matchingSessions.add(serverSession);
         }
      }
      return matchingSessions;
   }

   public synchronized Set<ServerSession> getSessions()
   {
      return new HashSet<ServerSession>(sessions.values());
   }

   @Override
   public boolean isInitialised()
   {
      synchronized (initialiseLock)
      {
         return initialised.getCount() < 1;
      }
   }

   @Override
   public boolean waitForInitialization(long timeout, TimeUnit unit) throws InterruptedException
   {
      CountDownLatch latch;
      synchronized (initialiseLock)
      {
         latch = initialised;
      }
      return latch.await(timeout, unit);
   }

   public HornetQServerControlImpl getHornetQServerControl()
   {
      return messagingServerControl;
   }

   public int getConnectionCount()
   {
      return remotingService.getConnections().size();
   }

   public PostOffice getPostOffice()
   {
      return postOffice;
   }

   public QueueFactory getQueueFactory()
   {
      return queueFactory;
   }

   public SimpleString getNodeID()
   {
      return nodeManager == null ? null : nodeManager.getNodeId();
   }

   public Queue createQueue(final SimpleString address,
                            final SimpleString queueName,
                            final SimpleString filterString,
                            final boolean durable,
                            final boolean temporary) throws Exception
   {
      return createQueue(address, queueName, filterString, durable, temporary, false);
   }

   public Queue locateQueue(SimpleString queueName) throws Exception
   {
      Binding binding = postOffice.getBinding(queueName);

      if (binding == null)
      {
         return null;
      }

      Bindable queue = binding.getBindable();

      if (!(queue instanceof Queue))
      {
         throw new IllegalStateException("locateQueue should only be used to locate queues");
      }

      return (Queue)binding.getBindable();
   }

   public Queue deployQueue(final SimpleString address,
                            final SimpleString queueName,
                            final SimpleString filterString,
                            final boolean durable,
                            final boolean temporary) throws Exception
   {
      HornetQLogger.LOGGER.deployQueue(queueName);

      return createQueue(address, queueName, filterString, durable, temporary, true);
   }

   public void destroyQueue(final SimpleString queueName, final ServerSession session) throws Exception
   {
      addressSettingsRepository.clearCache();

      Binding binding = postOffice.getBinding(queueName);

      if (binding == null)
      {
         throw HornetQMessageBundle.BUNDLE.noSuchQueue(queueName);
      }

      Queue queue = (Queue)binding.getBindable();

      if (queue.getConsumerCount() != 0)
      {
         HornetQMessageBundle.BUNDLE.cannotDeleteQueue(queue.getName(), queueName, binding.getClass().getName());
      }

      if (session != null)
      {
         if (queue.isDurable())
         {
            // make sure the user has privileges to delete this queue
            securityStore.check(binding.getAddress(), CheckType.DELETE_DURABLE_QUEUE, session);
         }
         else
         {
            securityStore.check(binding.getAddress(), CheckType.DELETE_NON_DURABLE_QUEUE, session);
         }
      }

      postOffice.removeBinding(queueName);

      queue.deleteAllReferences();

      if (queue.isDurable())
      {
         storageManager.deleteQueueBinding(queue.getID());
      }


      if (queue.getPageSubscription() != null)
      {
         queue.getPageSubscription().close();
      }

      PageSubscription subs = queue.getPageSubscription();

      if (subs != null)
      {
         subs.cleanupEntries(true);
      }
   }

   public synchronized void registerActivateCallback(final ActivateCallback callback)
   {
      activateCallbacks.add(callback);
   }

   public synchronized void unregisterActivateCallback(final ActivateCallback callback)
   {
      activateCallbacks.remove(callback);
   }

   public ExecutorFactory getExecutorFactory()
   {
      return executorFactory;
   }

   public void setGroupingHandler(final GroupingHandler groupingHandler)
   {
      this.groupingHandler = groupingHandler;
   }

   public GroupingHandler getGroupingHandler()
   {
      return groupingHandler;
   }

   public ReplicationEndpoint getReplicationEndpoint()
   {
      return replicationEndpoint;
   }

   public ReplicationManager getReplicationManager()
   {
      return replicationManager;
   }

   public ConnectorsService getConnectorsService()
   {
      return connectorsService;
   }

   public void deployDivert(DivertConfiguration config) throws Exception
   {
      if (config.getName() == null)
      {
         HornetQLogger.LOGGER.divertWithNoName();

         return;
      }

      if (config.getAddress() == null)
      {
         HornetQLogger.LOGGER.divertWithNoAddress();

         return;
      }

      if (config.getForwardingAddress() == null)
      {
         HornetQLogger.LOGGER.divertWithNoForwardingAddress();

         return;
      }

      SimpleString sName = new SimpleString(config.getName());

      if (postOffice.getBinding(sName) != null)
      {
         HornetQLogger.LOGGER.divertBindingNotExists(sName);

         return;
      }

      SimpleString sAddress = new SimpleString(config.getAddress());

      Transformer transformer = instantiateTransformer(config.getTransformerClassName());

      Filter filter = FilterImpl.createFilter(config.getFilterString());

      Divert divert = new DivertImpl(new SimpleString(config.getForwardingAddress()),
                                     sName,
                                     new SimpleString(config.getRoutingName()),
                                     config.isExclusive(),
                                     filter,
                                     transformer,
                                     postOffice,
                                     storageManager);

      Binding binding = new DivertBinding(storageManager.generateUniqueID(), sAddress, divert);

      postOffice.addBinding(binding);

      managementService.registerDivert(divert, config);
   }

   public void destroyDivert(SimpleString name) throws Exception
   {
      Binding binding = postOffice.getBinding(name);
      if (binding == null)
      {
         throw HornetQMessageBundle.BUNDLE.noBindingForDivert(name);
      }
      if (!(binding instanceof DivertBinding))
      {
         throw HornetQMessageBundle.BUNDLE.bindingNotDivert(name);
      }

      postOffice.removeBinding(name);
   }

   public void deployBridge(BridgeConfiguration config) throws Exception
   {
      if (clusterManager != null)
      {
         clusterManager.deployBridge(config, true);
      }
   }

   public void destroyBridge(String name) throws Exception
   {
      if (clusterManager != null)
      {
         clusterManager.destroyBridge(name);
      }
   }

   public ServerSession getSessionByID(String sessionName)
   {
      return sessions.get(sessionName);
   }

   // PUBLIC -------

   @Override
   public String toString()
   {
      if (identity != null)
      {
         return "HornetQServerImpl::" + identity;
      }
      else
      {
         return "HornetQServerImpl::" + (nodeManager != null ? "serverUUID=" + nodeManager.getUUID() : "");
      }
   }

   /**
    * For tests only, don't use this method as it's not part of the API
    * @param factory
    */
   public void replaceQueueFactory(QueueFactory factory)
   {
      this.queueFactory = factory;
   }


   private PagingManager createPagingManager()
   {

      return new PagingManagerImpl(new PagingStoreFactoryNIO(configuration.getPagingDirectory(),
                                                             configuration.getJournalBufferSize_NIO(),
                                                             scheduledPool,
                                                             executorFactory,
                                                             configuration.isJournalSyncNonTransactional(),
                                                             shutdownOnCriticalIO),
                                   storageManager,
                                   addressSettingsRepository);
   }

   /**
    * This method is protected as it may be used as a hook for creating a custom storage manager (on tests for instance)
    */
   private StorageManager createStorageManager()
   {
      if (configuration.isPersistenceEnabled())
      {
         return new JournalStorageManager(configuration, executorFactory, shutdownOnCriticalIO);
      }
      else
      {
         return new NullStorageManager();
      }
   }

   // Private
   // --------------------------------------------------------------------------------------

   private void callActivateCallbacks()
   {
      for (ActivateCallback callback : activateCallbacks)
      {
         callback.activated();
      }
   }

   private void callPreActiveCallbacks()
   {
      for (ActivateCallback callback : activateCallbacks)
      {
         callback.preActivate();
      }
   }


   /**
    * Starts everything apart from RemotingService and loading the data.
    */
   private void initialisePart1() throws Exception
   {
      if (state == SERVER_STATE.STOPPED)
         return;
      // Create the pools - we have two pools - one for non scheduled - and another for scheduled

      ThreadFactory tFactory = new HornetQThreadFactory("HornetQ-server-" + this.toString(),
                                                        false,
                                                        getThisClassLoader());

      if (configuration.getThreadPoolMaxSize() == -1)
      {
         threadPool = Executors.newCachedThreadPool(tFactory);
      }
      else
      {
         threadPool = Executors.newFixedThreadPool(configuration.getThreadPoolMaxSize(), tFactory);
      }

      executorFactory = new OrderedExecutorFactory(threadPool);

      scheduledPool = new ScheduledThreadPoolExecutor(configuration.getScheduledThreadPoolMaxSize(),
                                                      new HornetQThreadFactory("HornetQ-scheduled-threads",
                                                                               false,
                                                                               getThisClassLoader()));

      managementService = new ManagementServiceImpl(mbeanServer, configuration);

      if (configuration.getMemoryMeasureInterval() != -1)
      {
         memoryManager = new MemoryManagerImpl(configuration.getMemoryWarningThreshold(),
                                               configuration.getMemoryMeasureInterval());

         memoryManager.start();
      }

      // Create the hard-wired components

      if (configuration.isFileDeploymentEnabled())
      {
         deploymentManager = new FileDeploymentManager(configuration.getFileDeployerScanPeriod());
      }

      callPreActiveCallbacks();

      // startReplication();

      storageManager = createStorageManager();

      if (ConfigurationImpl.DEFAULT_CLUSTER_USER.equals(configuration.getClusterUser()) && ConfigurationImpl.DEFAULT_CLUSTER_PASSWORD.equals(configuration.getClusterPassword()))
      {
         HornetQLogger.LOGGER.clusterSecurityRisk();
      }

      securityStore = new SecurityStoreImpl(securityRepository,
                                            securityManager,
                                            configuration.getSecurityInvalidationInterval(),
                                            configuration.isSecurityEnabled(),
                                            configuration.getClusterUser(),
                                            configuration.getClusterPassword(),
                                            managementService);

      queueFactory = new QueueFactoryImpl(executorFactory, scheduledPool, addressSettingsRepository, storageManager);

      pagingManager = createPagingManager();

      resourceManager = new ResourceManagerImpl((int)(configuration.getTransactionTimeout() / 1000),
                                                configuration.getTransactionTimeoutScanPeriod(),
                                                scheduledPool);
      postOffice = new PostOfficeImpl(this,
                                      storageManager,
                                      pagingManager,
                                      queueFactory,
                                      managementService,
                                      configuration.getMessageExpiryScanPeriod(),
                                      configuration.getMessageExpiryThreadPriority(),
                                      configuration.isWildcardRoutingEnabled(),
                                      configuration.getIDCacheSize(),
                                      configuration.isPersistIDCache(),
                                      addressSettingsRepository);

      // This can't be created until node id is set
      clusterManager = new ClusterManagerImpl(executorFactory,
                                              this,
                                              postOffice,
                                              scheduledPool,
                                              managementService,
                                              configuration,
                                              nodeManager.getUUID(),
                                              configuration.isBackup(),
                                              configuration.isClustered());

      clusterManager.deploy();

      remotingService = new RemotingServiceImpl(clusterManager, configuration, this, managementService, scheduledPool);

      messagingServerControl = managementService.registerServer(postOffice,
                                                                storageManager,
                                                                configuration,
                                                                addressSettingsRepository,
                                                                securityRepository,
                                                                resourceManager,
                                                                remotingService,
                                                                this,
                                                                queueFactory,
                                                                scheduledPool,
                                                                pagingManager,
                                                                configuration.isBackup());

      // Address settings need to deployed initially, since they're require on paging manager.start()

      if (configuration.isFileDeploymentEnabled())
      {
         addressSettingsDeployer = new AddressSettingsDeployer(deploymentManager, addressSettingsRepository);

         addressSettingsDeployer.start();
      }

      deployAddressSettingsFromConfiguration();

      storageManager.start();

      if (securityManager != null)
      {
         securityManager.start();
      }

      postOffice.start();

      pagingManager.start();

      managementService.start();

      resourceManager.start();

      // Deploy all security related config
      if (configuration.isFileDeploymentEnabled())
      {
         basicUserCredentialsDeployer = new BasicUserCredentialsDeployer(deploymentManager, securityManager);

         basicUserCredentialsDeployer.start();

         if (securityManager != null)
         {
            securityDeployer = new SecurityDeployer(deploymentManager, securityRepository);

            securityDeployer.start();
         }
      }

      deploySecurityFromConfiguration();

      deployGroupingHandlerConfiguration(configuration.getGroupingHandlerConfiguration());
   }

   /*
    * Load the data, and start remoting service so clients can connect
    */
   private void initialisePart2() throws Exception
   {
      // Load the journal and populate queues, transactions and caches in memory

      if (state == SERVER_STATE.STOPPED)
      {
         return;
      }

      pagingManager.reloadStores();

      JournalLoadInformation[] journalInfo = loadJournals();

      compareJournals(journalInfo);

      final ServerInfo dumper = new ServerInfo(this, pagingManager);

      long dumpInfoInterval = configuration.getServerDumpInterval();

      if (dumpInfoInterval > 0)
      {
         scheduledPool.scheduleWithFixedDelay(new Runnable()
         {
            public void run()
            {
               HornetQLogger.LOGGER.dumpServerInfo(dumper.dump());
            }
         }, 0, dumpInfoInterval, TimeUnit.MILLISECONDS);
      }

      // Deploy the rest of the stuff

      // Deploy any predefined queues
      if (configuration.isFileDeploymentEnabled())
      {
         queueDeployer = new QueueDeployer(deploymentManager, this);

         queueDeployer.start();
      }
      else
      {
         deployQueuesFromConfiguration();
      }

      // We need to call this here, this gives any dependent server a chance to deploy its own addresses
      // this needs to be done before clustering is fully activated
      callActivateCallbacks();

      // Deploy any pre-defined diverts
      deployDiverts();

      if (deploymentManager != null)
      {
         deploymentManager.start();
      }

      // We do this at the end - we don't want things like MDBs or other connections connecting to a backup server until
      // it is activated

      clusterManager.start();

      remotingService.start();

      initialised.countDown();
   }

   /**
    * @param journalInfo
    */
   private void compareJournals(final JournalLoadInformation[] journalInfo) throws Exception
   {
      if (replicationManager != null)
      {
         replicationManager.compareJournals(journalInfo);
      }
   }

   private void deploySecurityFromConfiguration()
   {
      for (Map.Entry<String, Set<Role>> entry : configuration.getSecurityRoles().entrySet())
      {
         securityRepository.addMatch(entry.getKey(), entry.getValue(), true);
      }
   }

   private void deployQueuesFromConfiguration() throws Exception
   {
      for (CoreQueueConfiguration config : configuration.getQueueConfigurations())
      {
         deployQueue(SimpleString.toSimpleString(config.getAddress()),
                     SimpleString.toSimpleString(config.getName()),
                     SimpleString.toSimpleString(config.getFilterString()),
                     config.isDurable(),
                     false);
      }
   }

   private void deployAddressSettingsFromConfiguration()
   {
      for (Map.Entry<String, AddressSettings> entry : configuration.getAddressesSettings().entrySet())
      {
         addressSettingsRepository.addMatch(entry.getKey(), entry.getValue(), true);
      }
   }

   private JournalLoadInformation[] loadJournals() throws Exception
   {
      JournalLoadInformation[] journalInfo = new JournalLoadInformation[2];

      List<QueueBindingInfo> queueBindingInfos = new ArrayList<QueueBindingInfo>();

      List<GroupingInfo> groupingInfos = new ArrayList<GroupingInfo>();

      journalInfo[0] = storageManager.loadBindingJournal(queueBindingInfos, groupingInfos);

      recoverStoredConfigs();

      Map<Long, Queue> queues = new HashMap<Long, Queue>();
      Map<Long, QueueBindingInfo> queueBindingInfosMap = new HashMap<Long, QueueBindingInfo>();

      for (QueueBindingInfo queueBindingInfo : queueBindingInfos)
      {
         queueBindingInfosMap.put(queueBindingInfo.getId(), queueBindingInfo);

         if (queueBindingInfo.getFilterString() == null || !queueBindingInfo.getFilterString()
                                                                            .toString()
                                                                            .equals(GENERIC_IGNORED_FILTER))
         {
            Filter filter = FilterImpl.createFilter(queueBindingInfo.getFilterString());

            PageSubscription subscription = pagingManager.getPageStore(queueBindingInfo.getAddress())
                                                         .getCursorProvider()
                                                         .createSubscription(queueBindingInfo.getId(), filter, true);

            Queue queue = queueFactory.createQueue(queueBindingInfo.getId(),
                                                   queueBindingInfo.getAddress(),
                                                   queueBindingInfo.getQueueName(),
                                                   filter,
                                                   subscription,
                                                   true,
                                                   false);

            Binding binding = new LocalQueueBinding(queueBindingInfo.getAddress(), queue, nodeManager.getNodeId());

            queues.put(queueBindingInfo.getId(), queue);

            postOffice.addBinding(binding);

            managementService.registerAddress(queueBindingInfo.getAddress());
            managementService.registerQueue(queue, queueBindingInfo.getAddress(), storageManager);
         }

      }

      for (GroupingInfo groupingInfo : groupingInfos)
      {
         if (groupingHandler != null)
         {
            groupingHandler.addGroupBinding(new GroupBinding(groupingInfo.getId(),
                                                             groupingInfo.getGroupId(),
                                                             groupingInfo.getClusterName()));
         }
      }

      Map<SimpleString, List<Pair<byte[], Long>>> duplicateIDMap = new HashMap<SimpleString, List<Pair<byte[], Long>>>();

      HashSet<Pair<Long, Long>> pendingLargeMessages = new HashSet<Pair<Long, Long>>();

      journalInfo[1] = storageManager.loadMessageJournal(postOffice,
                                                         pagingManager,
                                                         resourceManager,
                                                         queues,
                                                         queueBindingInfosMap,
                                                         duplicateIDMap,
                                                         pendingLargeMessages);

      for (Map.Entry<SimpleString, List<Pair<byte[], Long>>> entry : duplicateIDMap.entrySet())
      {
         SimpleString address = entry.getKey();

         DuplicateIDCache cache = postOffice.getDuplicateIDCache(address);

         if (configuration.isPersistIDCache())
         {
            cache.load(entry.getValue());
         }
      }

      for (Pair<Long, Long> msgToDelete : pendingLargeMessages)
      {
         HornetQLogger.LOGGER.deletingPendingMessage(msgToDelete);
         LargeServerMessage msg = storageManager.createLargeMessage();
         msg.setMessageID(msgToDelete.getB());
         msg.setPendingRecordID(msgToDelete.getA());
         msg.setDurable(true);
         msg.deleteFile();
      }

      return journalInfo;
   }

   /**
    * @throws Exception
    */
   private void recoverStoredConfigs() throws Exception
   {
      List<PersistedAddressSetting> adsettings = storageManager.recoverAddressSettings();
      for (PersistedAddressSetting set : adsettings)
      {
         addressSettingsRepository.addMatch(set.getAddressMatch().toString(), set.getSetting());
      }

      List<PersistedRoles> roles = storageManager.recoverPersistedRoles();

      for (PersistedRoles roleItem : roles)
      {
         Set<Role> setRoles = SecurityFormatter.createSecurity(roleItem.getSendRoles(),
                                                               roleItem.getConsumeRoles(),
                                                               roleItem.getCreateDurableQueueRoles(),
                                                               roleItem.getDeleteDurableQueueRoles(),
                                                               roleItem.getCreateNonDurableQueueRoles(),
                                                               roleItem.getDeleteNonDurableQueueRoles(),
                                                               roleItem.getManageRoles());

         securityRepository.addMatch(roleItem.getAddressMatch().toString(), setRoles);
      }
   }

   private Queue createQueue(final SimpleString address,
                             final SimpleString queueName,
                             final SimpleString filterString,
                             final boolean durable,
                             final boolean temporary,
                             final boolean ignoreIfExists) throws Exception
   {
      QueueBinding binding = (QueueBinding)postOffice.getBinding(queueName);

      if (binding != null)
      {
         if (ignoreIfExists)
         {
            return binding.getQueue();
         }
         else
         {
            throw HornetQMessageBundle.BUNDLE.queueAlreadyExists(queueName);
         }
      }

      Filter filter = FilterImpl.createFilter(filterString);

      long txID = storageManager.generateUniqueID();;
      long queueID = storageManager.generateUniqueID();

      PageSubscription pageSubscription;

      if (filterString != null && filterString.toString().equals(GENERIC_IGNORED_FILTER))
      {
         pageSubscription = null;
      }
      else
      {
         pageSubscription = pagingManager.getPageStore(address)
                                         .getCursorProvider()
                                         .createSubscription(queueID, filter, durable);
      }

      final Queue queue = queueFactory.createQueue(queueID,
                                                   address,
                                                   queueName,
                                                   filter,
                                                   pageSubscription,
                                                   durable,
                                                   temporary);

      binding = new LocalQueueBinding(address, queue, nodeManager.getNodeId());

      if (durable)
      {
         storageManager.addQueueBinding(txID, binding);
      }

      try
      {
         postOffice.addBinding(binding);
         if (durable)
         {
            storageManager.commitBindings(txID);
         }
      }
      catch (Exception e)
      {
         if (durable)
         {
            storageManager.rollbackBindings(txID);
         }
         queue.close();
         pageSubscription.close();
         throw e;
      }


      managementService.registerAddress(address);
      managementService.registerQueue(queue, address, storageManager);

      return queue;
   }

   private void deployDiverts() throws Exception
   {
      for (DivertConfiguration config : configuration.getDivertConfigurations())
      {
         deployDivert(config);
      }
   }

   private void deployGroupingHandlerConfiguration(final GroupingHandlerConfiguration config) throws Exception
   {
      if (config != null)
      {
         GroupingHandler groupingHandler;
         if (config.getType() == GroupingHandlerConfiguration.TYPE.LOCAL)
         {
            groupingHandler = new LocalGroupingHandler(managementService,
                                                       config.getName(),
                                                       config.getAddress(),
                                                       getStorageManager(),
                                                       config.getTimeout());
         }
         else
         {
            groupingHandler = new RemoteGroupingHandler(managementService,
                                                        config.getName(),
                                                        config.getAddress(),
                                                        config.getTimeout());
         }

         this.groupingHandler = groupingHandler;

         managementService.addNotificationListener(groupingHandler);
      }
   }

   private Transformer instantiateTransformer(final String transformerClassName)
   {
      Transformer transformer = null;

      if (transformerClassName != null)
      {
         transformer = (Transformer)instantiateInstance(transformerClassName);
      }

      return transformer;
   }

   private Object instantiateInstance(final String className)
   {
       return safeInitNewInstance(className);
   }

   private static ClassLoader getThisClassLoader()
   {
      return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>()
      {
         public ClassLoader run()
         {
            return ClientSessionFactoryImpl.class.getClassLoader();
         }
      });

   }

   /**
    * Check if journal directory exists or create it (if configured to do so)
    */
   private void checkJournalDirectory()
   {
      File journalDir = new File(configuration.getJournalDirectory());

      if (!journalDir.exists())
      {
         if (configuration.isCreateJournalDir())
         {
            journalDir.mkdirs();
         }
         else
         {
            throw HornetQMessageBundle.BUNDLE.cannotCreateDir(journalDir.getAbsolutePath());
         }
      }
   }

   /**
    * To be called by backup trying to fail back the server
    */
   private void startFailbackChecker()
   {
      scheduledPool.scheduleAtFixedRate(new FailbackChecker(), 1000l, 1000l, TimeUnit.MILLISECONDS);
   }

   // Inner classes
   // --------------------------------------------------------------------------------

   private class FailbackChecker implements Runnable
   {
      private boolean restarting = false;

      public void run()
      {
         try
         {
            if (!restarting && nodeManager.isAwaitingFailback())
            {
               HornetQLogger.LOGGER.awaitFailBack();
               restarting = true;
               Thread t = new Thread(new Runnable()
               {
                  public void run()
                  {
                     try
                     {
                        HornetQLogger.LOGGER.debug(HornetQServerImpl.this + "::Stopping live node in favor of failback");
                        stop(true);
                        // We need to wait some time before we start the backup again
                        // otherwise we may eventually start before the live had a chance to get it
                        Thread.sleep(configuration.getFailbackDelay());
                        configuration.setBackup(true);
                        HornetQLogger.LOGGER.debug(HornetQServerImpl.this + "::Starting backup node now after failback");
                        start();
                     }
                     catch (Exception e)
                     {
                        HornetQLogger.LOGGER.serverRestartWarning();
                     }
                  }
               });
               t.start();
            }
         }
         catch (Exception e)
         {
            HornetQLogger.LOGGER.serverRestartWarning(e);
         }
      }
   }

   private final class SharedStoreLiveActivation implements Activation
   {
      public void run()
      {
         try
         {
            HornetQLogger.LOGGER.awaitingLiveLock();

            checkJournalDirectory();

            if (HornetQLogger.LOGGER.isDebugEnabled())
            {
               HornetQLogger.LOGGER.debug("First part initialization on " + this);
            }

            initialisePart1();

            if (nodeManager.isBackupLive())
            {
               /*
                * looks like we've failed over at some point need to inform that we are the backup
                * so when the current live goes down they failover to us
                */
               if (HornetQLogger.LOGGER.isDebugEnabled())
               {
                  HornetQLogger.LOGGER.debug("announcing backup to the former live" + this);
               }

               clusterManager.announceBackup();
               Thread.sleep(configuration.getFailbackDelay());
            }

            nodeManager.startLiveNode();

            if (state == SERVER_STATE.STOPPED)
            {
               return;
            }

            initialisePart2();

            HornetQLogger.LOGGER.serverIsLive();
         }
         catch (Exception e)
         {
            HornetQLogger.LOGGER.initializationError(e);
         }
      }

      public void close(boolean permanently) throws Exception
      {
         if (permanently)
         {
            nodeManager.crashLiveServer();
         }
         else
         {
            nodeManager.pauseLiveServer();
         }
      }
   }

   private final class SharedStoreBackupActivation implements Activation
   {
      public void run()
      {
         try
         {
            nodeManager.startBackup();

            initialisePart1();

            clusterManager.start();

            state = SERVER_STATE.STARTED;

            HornetQLogger.LOGGER.backupServerStarted(version.getFullVersion(), nodeManager.getNodeId());

            nodeManager.awaitLiveNode();

            configuration.setBackup(false);

            if (state != SERVER_STATE.STARTED)
            {
               return;
            }

            initialisePart2();

            clusterManager.activate();

            HornetQLogger.LOGGER.backupServerIsLive();

            nodeManager.releaseBackup();
            if (configuration.isAllowAutoFailBack())
            {
               startFailbackChecker();
            }
         }
         catch (InterruptedException e)
         {
            // this is ok, we are being stopped
         }
         catch (ClosedChannelException e)
         {
            // this is ok too, we are being stopped
         }
         catch (Exception e)
         {
            if (!(e.getCause() instanceof InterruptedException))
            {
               HornetQLogger.LOGGER.initializationError(e);
            }
         }
         catch (Throwable e)
         {
            HornetQLogger.LOGGER.initializationError(e);
         }
      }

      public void close(boolean permanently) throws Exception
      {
         if (configuration.isBackup())
         {
            long timeout = 30000;

            long start = System.currentTimeMillis();

            while (backupActivationThread.isAlive() && System.currentTimeMillis() - start < timeout)
            {
               nodeManager.interrupt();

               backupActivationThread.interrupt();

               backupActivationThread.join(1000);

            }

            if (System.currentTimeMillis() - start >= timeout)
            {
               threadDump("Timed out waiting for backup activation to exit");
            }

            nodeManager.stopBackup();
         }
         else
         {
            // if we are now live, behave as live
            // We need to delete the file too, otherwise the backup will failover when we shutdown or if the backup is
            // started before the live
            if (permanently)
            {
               nodeManager.crashLiveServer();
            }
            else
            {
               nodeManager.pauseLiveServer();
            }
         }
      }
   }

   private final class ShutdownOnCriticalErrorListener implements IOCriticalErrorListener
   {
      boolean failedAlready = false;

      public synchronized void onIOException(HornetQExceptionType code, String message, SequentialFile file)
      {
         if (!failedAlready)
         {
            failedAlready = true;

            HornetQLogger.LOGGER.ioErrorShutdownServer(code, message);

            new Thread()
            {
               @Override
               public void run()
               {
                  try
                  {
                     HornetQServerImpl.this.stop(true, true);
                  }
                  catch (Exception e)
                  {
                     HornetQLogger.LOGGER.errorStoppingServer(e);
                  }
               }
            }.start();
         }
      }
   }

   private interface Activation extends Runnable
   {
      void close(boolean permanently) throws Exception;
   }

   private final class SharedNothingBackupActivation implements Activation
   {
      private ServerLocatorInternal serverLocator0;
      private volatile boolean failedToConnect;
      private QuorumManager quorumManager;

      public void run()
      {
         try
         {
            nodeManager.startBackup();

            initialisePart1();

            final String liveConnectorName = configuration.getLiveConnectorName();
            if (liveConnectorName == null)
            {
               throw HornetQMessageBundle.BUNDLE.noLiveForReplicatedBackup();
            }
            clusterManager.start();

            final TransportConfiguration tp = configuration.getConnectorConfigurations().get(liveConnectorName);
            serverLocator0 = (ServerLocatorInternal)HornetQClient.createServerLocatorWithHA(tp);
            quorumManager = new QuorumManager(HornetQServerImpl.this, serverLocator0, threadPool, getIdentity());
            replicationEndpoint.setQuorumManager(quorumManager);

            serverLocator0.setReconnectAttempts(-1);
            serverLocator0.addInterceptor(new ReplicationError(HornetQServerImpl.this));
            threadPool.execute(new Runnable()
            {
               @Override
               public void run()
               {
                  try
                  {
                     final ClientSessionFactoryInternal liveServerSessionFactory = serverLocator0.connect();
                     if (liveServerSessionFactory == null)
                     {
                        throw new RuntimeException("Could not estabilish the connection");
                     }

                     liveServerSessionFactory.setReconnectAttempts(1);
                     quorumManager.setSessionFactory(liveServerSessionFactory);
                     CoreRemotingConnection liveConnection = liveServerSessionFactory.getConnection();
                     quorumManager.addAsFailureListenerOf(liveConnection);
                     Channel pingChannel = liveConnection.getChannel(CHANNEL_ID.PING.id, -1);
                     Channel replicationChannel = liveConnection.getChannel(CHANNEL_ID.REPLICATION.id, -1);
                     connectToReplicationEndpoint(replicationChannel);
                     replicationEndpoint.start();
                     clusterManager.announceReplicatingBackup(pingChannel);
                  }
                  catch (Exception e)
                  {
                     HornetQLogger.LOGGER.replicationStartProblem(e);
                     failedToConnect = true;
                     quorumManager.causeExit();
                     try
                     {
                        if (state != SERVER_STATE.STOPPED)
                           HornetQServerImpl.this.stop();
                        return;
                     }
                     catch (Exception e1)
                     {
                        throw new RuntimeException(e1);
                     }
                  }
               }
            });

            HornetQLogger.LOGGER.backupServerStarted(version.getFullVersion(), nodeManager.getNodeId());
            state = SERVER_STATE.STARTED;

            // Server node (i.e. Live node) is not running, now the backup takes over.
            // we must remember to close stuff we don't need any more
            if (failedToConnect)
                  return;
            /**
             * Wait for a shutdown order or for the live to fail. All the action happens inside
             * {@link QuorumManager}
             */
            QuorumManager.BACKUP_ACTIVATION signal = quorumManager.waitForStatusChange();

            serverLocator0.close();
            stopComponent(replicationEndpoint);

            if (failedToConnect || !isStarted() || signal == BACKUP_ACTIVATION.STOP)
               return;

            if (!isRemoteBackupUpToDate())
            {
               throw HornetQMessageBundle.BUNDLE.backupServerNotInSync();
            }

            configuration.setBackup(false);
            synchronized (startUpLock)
            {
               if (!isStarted())
                  return;
               HornetQLogger.LOGGER.becomingLive(HornetQServerImpl.this);
               storageManager.start();
               initialisePart2();
               clusterManager.activate();
            }

         }
         catch (Exception e)
         {
            if ((e instanceof InterruptedException || e instanceof IllegalStateException) && !isStarted())
               // do not log these errors if the server is being stopped.
               return;
            HornetQLogger.LOGGER.initializationError(e);
            e.printStackTrace();
         }
      }

      public void close(final boolean permanently) throws Exception
      {
         if (quorumManager != null)
            quorumManager.causeExit();

         if (serverLocator0 != null)
         {
            serverLocator0.close();
         }

         if (configuration.isBackup())
         {
            long timeout = 30000;

            long start = System.currentTimeMillis();

            while (backupActivationThread.isAlive() && System.currentTimeMillis() - start < timeout)
            {
               nodeManager.interrupt();

               backupActivationThread.interrupt();

               Thread.sleep(1000);
            }

            if (System.currentTimeMillis() - start >= timeout)
            {
               HornetQLogger.LOGGER.backupActivationProblem();
            }

            nodeManager.stopBackup();
         }
      }

      /**
       * Live has notified this server that it is going to stop.
       */
      public void failOver()
      {
         quorumManager.failOver();
      }
   }


   private final class SharedNothingLiveActivation implements Activation
   {
      public void run()
      {
         try
         {
            initialisePart1();

            initialisePart2();

            if (identity != null)
            {
               HornetQLogger.LOGGER.serverIsLive(identity);
            }
            else
            {
               HornetQLogger.LOGGER.serverIsLive();
            }
         }
         catch (Exception e)
         {
            HornetQLogger.LOGGER.initializationError(e);
         }
      }

      public void close(boolean permanently) throws Exception
      {
         if (permanently)
         {
            nodeManager.crashLiveServer();
         }
         else
         {
            nodeManager.pauseLiveServer();
         }
      }
   }

   /** This seems duplicate code all over the place, but for security reasons we can't let something like this to be open in a
    *  utility class, as it would be a door to load anything you like in a safe VM.
    *  For that reason any class trying to do a privileged block should do with the AccessController directly.
    */
   private static Object safeInitNewInstance(final String className)
   {
      return AccessController.doPrivileged(new PrivilegedAction<Object>()
      {
         public Object run()
         {
            return ClassloadingUtil.newInstanceFromClassLoader(className);
         }
      });
   }

   @Override
   public void startReplication(CoreRemotingConnection rc, ClusterConnection clusterConnection,
                             Pair<TransportConfiguration, TransportConfiguration> pair) throws HornetQException
   {
      if (replicationManager != null)
      {
         throw new AlreadyReplicatingException();
      }

      if (!isStarted())
      {
         throw new IllegalStateException();
      }

      synchronized (replicationLock)
      {

         if (replicationManager != null)
         {
            throw new AlreadyReplicatingException();
         }
         ReplicationFailureListener listener = new ReplicationFailureListener();
         rc.addCloseListener(listener);
         rc.addFailureListener(listener);
         replicationManager = new ReplicationManager(rc, executorFactory);

         try
         {
            replicationManager.start();
            storageManager.startReplication(replicationManager, pagingManager, getNodeID().toString(),
                                            clusterConnection, pair);
         }
         catch (Exception e)
         {
            /*
             * The reasoning here is that the exception was either caused by (1) the (interaction
             * with) the backup, or (2) by an IO Error at the storage. If (1), we can swallow the
             * exception and ignore the replication request. If (2) the live will crash shortly.
             */
            HornetQLogger.LOGGER.errorStartingReplication(e);

            try
            {
               if (replicationManager != null)
                  replicationManager.stop();
            }
            catch (Exception hqe)
            {
               HornetQLogger.LOGGER.errorStoppingReplication(hqe);
            }
            finally
            {
               replicationManager = null;
            }

            if (e instanceof HornetQException)
            {
               throw (HornetQException)e;
            }

            throw HornetQMessageBundle.BUNDLE.replicationStartError(e);
         }
      }
   }

   /**
    * Whether a remote backup server was in sync with its live server. If it was not in sync, it may
    * not take over the live's functions.
    * <p>
    * A local backup server or a live server should always return {@code true}
    * @return whether the backup is up-to-date, if the server is not a backup it always returns
    *         {@code true}.
    */
   public boolean isRemoteBackupUpToDate()
   {
      return backupUpToDate;
   }

   public void setRemoteBackupUpToDate(String nodeID)
   {
      nodeManager.setNodeID(nodeID);
      clusterManager.announceBackup();
      backupUpToDate = true;
   }

   private final class ReplicationFailureListener implements FailureListener, CloseListener
   {

      @Override
      public void connectionFailed(HornetQException exception, boolean failedOver)
      {
         connectionClosed();
      }

      @Override
      public void connectionClosed()
      {
         Executors.newSingleThreadExecutor().execute(new Runnable()
         {
            public void run()
            {
               synchronized (replicationLock)
               {
                  if (replicationManager != null)
                  {
                     storageManager.stopReplication();
                  replicationManager = null;
               }
            }
            }
         });
      }
   }

   /**
    * @throws HornetQException
    */
   public void remoteFailOver() throws HornetQException
   {
      if (!configuration.isBackup() || configuration.isSharedStore())
      {
         throw new InternalErrorException();
      }
      if (!backupUpToDate) return;
      if (activation instanceof SharedNothingBackupActivation)
      {
         ((SharedNothingBackupActivation)activation).failOver();
      }
   }
}
