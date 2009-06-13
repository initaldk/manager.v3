// Copyright (C) 2009 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.instantiator;

import com.google.enterprise.connector.common.PropertiesUtils;
import com.google.enterprise.connector.common.StringUtils;
import com.google.enterprise.connector.manager.Context;
import com.google.enterprise.connector.persist.ConnectorExistsException;
import com.google.enterprise.connector.persist.ConnectorNotFoundException;
import com.google.enterprise.connector.pusher.Pusher;
import com.google.enterprise.connector.scheduler.BatchResultRecorder;
import com.google.enterprise.connector.scheduler.Cancelable;
import com.google.enterprise.connector.scheduler.CancelableBatch;
import com.google.enterprise.connector.scheduler.TaskHandle;
import com.google.enterprise.connector.scheduler.ThreadPool;
import com.google.enterprise.connector.spi.AuthenticationManager;
import com.google.enterprise.connector.spi.AuthorizationManager;
import com.google.enterprise.connector.spi.ConfigureResponse;
import com.google.enterprise.connector.spi.Connector;
import com.google.enterprise.connector.spi.ConnectorFactory;
import com.google.enterprise.connector.spi.ConnectorShutdownAware;
import com.google.enterprise.connector.spi.ConnectorType;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.traversal.BatchResult;
import com.google.enterprise.connector.traversal.Traverser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ConnectorCoordinator that supports Spring based connector instantiation and
 * persistent storage of connector configuration, schedule and traversal state.
 */
public class ConnectorCoordinatorImpl implements ConnectorCoordinator {
  private static final Logger LOGGER =
      Logger.getLogger(ConnectorCoordinatorImpl.class.getName());

  /**
   * Invariant context.
   */
  private final String name;
  private final Pusher pusher;
  private final ThreadPool threadPool;

  /**
   * Context set when an instance is created or configured and cleared when the
   * instance is removed. It is an invariant that either both of these are null
   * or neither is.
   */
  private TypeInfo typeInfo;

  // TODO(strellis): Simplify the connector checkpoint storage.
  //
  // The connector checkpoint is ultimately held in a backing
  // ConnectorStateStore which can be accessed through instanceInfo.
  // instanceInfo wraps the backing stateStore in a GenerationalStore to avoid
  // concurrency problems related to a traversal that was started before a
  // connector reconfiguration/traversal restart changing the checkpoint.
  // The methods store/getTraversalState access checkpoints through
  // instanceInfo directly. These methods are currently only used by
  // restartConnectorTraversal.
  //
  // In addition ConnectorInterfaces holds a reference to a
  // ConnectorTraversalStore which wraps the backing ConnectorStateStore
  // held by instanceInfo. This ConnectorTraversalStateStore provides
  // needed integration with the GenerationalStore mechanism. It also implements
  // the TraversalStateStore interface rather than the ConnectorStateStore
  // interface. The QueryTraverser accesses checkpoint information through
  // the ConnectorTraversalStore that is wrapped in ConnectorInterfaces and
  // is backed by the same ConnectorStateStore held by instanceInfo.
  private InstanceInfo instanceInfo;

  /**
   * Context that is filled in on first use. Requires instanceInfo.
   */
  private ConnectorInterfaces interfaces;

  /**
   * Context set when a batch is run.
   */
  private TaskHandle taskHandle;
  private Object batchKey;
  private BatchResultRecorder batchResultRecorder;

  ConnectorCoordinatorImpl(String name, Pusher pusher, ThreadPool threadPool) {
    this.name = name;
    this.pusher = pusher;
    this.threadPool = threadPool;
  }

  ConnectorCoordinatorImpl(InstanceInfo instanceInfo, Pusher pusher,
      ThreadPool threadPool) {
    this.name = instanceInfo.getName();
    this.pusher = pusher;
    this.threadPool = threadPool;
    this.instanceInfo = instanceInfo;
  }

  public String getName() {
    return name;
  }

  public synchronized void removeConnector() {
    LOGGER.info("Dropping connector: " + name);
    try {
      if (!hasInstanceInfo()) {
        return;
      }
      shutdownConnector(true);
      instanceInfo.removeConnector();
      removeConnectorDirectory(name,
          makeConnectorDirectoryFile(name, typeInfo), typeInfo);
    } finally {
      resetInterfaces();
      resetInstanceInfo();
      resetTypeInfo();
    }
  }

  public synchronized boolean exists() {
    return hasInstanceInfo();
  }

  public synchronized AuthenticationManager getAuthenticationManager()
      throws ConnectorNotFoundException, InstantiatorException {
    return getConnectorInterfaces().getAuthenticationManager();
  }

  public synchronized AuthorizationManager getAuthorizationManager()
      throws ConnectorNotFoundException, InstantiatorException {
    return getConnectorInterfaces().getAuthorizationManager();
  }

  public synchronized Traverser getTraverser()
      throws ConnectorNotFoundException, InstantiatorException {
    return getConnectorInterfaces().getTraverser();
  }

  public synchronized ConfigureResponse getConfigForm(Locale locale)
      throws ConnectorNotFoundException, InstantiatorException {
    ConnectorType connectorType = typeInfo.getConnectorType();
    Map<String, String> configMap = getInstanceInfo().getConnectorConfig();
    try {
      return connectorType.getPopulatedConfigForm(configMap, locale);
    } catch (Exception e) {
      throw new InstantiatorException("Failed to get configuration form", e);
    }
  }

  public synchronized void restartConnectorTraversal() {
    storeTraversalState(null);
    resetInterfaces();
  }

  public synchronized void setConnectorSchedule(String connectorSchedule)
      throws ConnectorNotFoundException {
    getInstanceInfo().setConnectorSchedule(connectorSchedule);
  }

  public synchronized String getConnectorSchedule()
      throws ConnectorNotFoundException {
    return getInstanceInfo().getConnectorSchedule();
  }

  // TODO (strellis): Should store/getTraversalState be called
  // get/setTraversalState and throw ConnectorNotFoundException for consistency
  // with other methods or should they retain their historical names and
  // behavior as defined by TraversalStateStore.
  public synchronized void storeTraversalState(String traversalState) {
    if (instanceInfo != null) {
      instanceInfo.setConnectorState(traversalState);
    }
  }

  public synchronized String getTraversalState() {
    if (instanceInfo == null) {
      return null;
    } else {
      return instanceInfo.getConnectorState();
    }
  }

  public synchronized String getConnectorTypeName()
      throws ConnectorNotFoundException {
    return getInstanceInfo().getTypeInfo().getConnectorTypeName();
  }

  public synchronized ConfigureResponse setConnectorConfig(
      TypeInfo newTypeInfo, Map<String, String> configMap, Locale locale,
      boolean update) throws ConnectorNotFoundException,
      ConnectorExistsException, InstantiatorException {
    LOGGER.info("Configuring connector: " + name);
    resetInterfaces();
    ConfigureResponse response = null;
    if (hasInstanceInfo()) {
      if (!update) {
        throw new ConnectorExistsException();
      }
      if (newTypeInfo.getConnectorTypeName().equals(
          typeInfo.getConnectorTypeName())) {
        File connectorDir = instanceInfo.getConnectorDir();
        response = resetConfig(connectorDir, typeInfo, configMap, locale);
      } else {
        // An existing connector is being given a new type - drop then add.
        removeConnector();
        response = createNewConnector(newTypeInfo, configMap, locale);
        if (response != null) {
          // TODO: We need to restore original Connector config. This is
          // necessary once we allow update a Connector with new ConnectorType.
          LOGGER.severe("Failed to update Connector config."
              + " Need to restore original Connector config.");
        }
      }
    } else {
      if (update) {
        throw new ConnectorNotFoundException();
      }
      response = createNewConnector(newTypeInfo, configMap, locale);
    }
    return response;
  }

  public synchronized Map<String, String> getConnectorConfig()
      throws ConnectorNotFoundException {
    return getInstanceInfo().getConnectorConfig();
  }

  public synchronized void startBatch(BatchResultRecorder resultRecorder,
      int batchHint) throws ConnectorNotFoundException {
    verifyConnectorInstanceAvailable();
    if (taskHandle != null && !taskHandle.isDone()) {
      return; // TODO(strellis): Return false?
    }
    taskHandle = null;
    batchKey = new Object();
    try {
      Traverser traverser = getTraverser();
      Cancelable batch =
          new CancelableBatch(traverser, name, resultRecorder, batchHint);
      taskHandle = threadPool.submit(batch);
    } catch (ConnectorNotFoundException cnfe) {
      LOGGER.log(Level.WARNING, "Connector not found - this is normal if you "
          + " recently reconfigured your connector instance." + cnfe);
    } catch (InstantiatorException ie) {
      LOGGER.log(Level.WARNING, "Connector not found - this is normal if you "
          + " recently reconfigured your connector instance." + ie);
    }
  }

  public synchronized void completeBatch(Object batchKey,
      String connectorSchedule, BatchResult batchResult)
      throws ConnectorNotFoundException {
    if (this.batchKey == batchKey) {
      if (connectorSchedule != null) {
        setConnectorSchedule(connectorSchedule);
      }
      // TODO(strellis): Host Load manager needs additional synchronization.
      // Take care not to introduce inconsistent lock order deadlocks.
      batchResultRecorder.recordResult(batchResult);
    }
  }

  public synchronized void shutdown() {
    shutdownConnector(false);
    resetInstanceInfo();
    resetInterfaces();
  }

  public synchronized void cancelBatch(Object batchKey) {
    if (this.batchKey == batchKey) {
      resetBatch();
      resetInterfaces();
    }
  }

  private void resetBatch() {
    if (taskHandle != null) {
      taskHandle.cancel();
    }
    taskHandle = null;
    batchKey = null;
    batchResultRecorder = null;
  }

  private void shutdownConnector(boolean delete) {
    if (instanceInfo != null
        && instanceInfo.getConnector() instanceof ConnectorShutdownAware) {
      ConnectorShutdownAware csa =
          (ConnectorShutdownAware) instanceInfo.getConnector();
      try {
        csa.shutdown();
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Problem shutting down connector " + name
            + " during configuration update.", e);
      }

      if (delete) {
        try {
          LOGGER.fine("Removing Connector " + name);
          csa.delete();
        } catch (Exception e) {
          LOGGER.log(Level.WARNING, "Failed to remove connector " + name, e);
        }
      }
    }
  }

  private void resetInterfaces() {
    interfaces = null;
  }

  private boolean hasInstanceInfo() {
    return instanceInfo != null;
  }

  private void resetInstanceInfo() {
    instanceInfo = null;
  }

  private void resetTypeInfo() {
    typeInfo = null;
  }

  // Visible for testing.
  InstanceInfo getInstanceInfo() throws ConnectorNotFoundException {
    verifyConnectorInstanceAvailable();
    return instanceInfo;

  }

  private void verifyConnectorInstanceAvailable()
      throws ConnectorNotFoundException {
    if (instanceInfo == null) {
      throw new ConnectorNotFoundException("Connector instance " + name
          + " not avaliable.");
    }
  }

  private ConnectorInterfaces getConnectorInterfaces()
      throws ConnectorNotFoundException {
    if (interfaces == null) {
      InstanceInfo info = getInstanceInfo();
      interfaces =
          new ConnectorInterfaces(name, info.getConnector(), pusher, info
              .getTraversalStateStore());
    }
    return interfaces;
  }

  private ConfigureResponse createNewConnector(TypeInfo newTypeInfo,
      Map<String, String> config, Locale locale) throws InstantiatorException {
    if (typeInfo != null) {
      throw new IllegalStateException("Create new connector with type set");
    }
    if (hasInstanceInfo()) {
      throw new IllegalStateException("Create new connector with existing set");
    }
    File connectorDir = makeConnectorDirectory(name, newTypeInfo);
    try {
      ConfigureResponse result = null;
      result = resetConfig(connectorDir, newTypeInfo, config, locale);
      if (result != null && result.getMessage() != null) {
        removeConnectorDirectory(name, connectorDir, typeInfo);
      }
      return result;
    } catch (InstantiatorException ie) {
      removeConnectorDirectory(name, connectorDir, typeInfo);
      throw (ie);
    }
  }

  private ConfigureResponse resetConfig(File connectorDir,
      TypeInfo newTypeInfo, Map<String, String> proposedConfig, Locale locale)
      throws InstantiatorException {

    // Copy the configuration map, adding a couple of additional
    // context properties. validateConfig() may also alter this map.
    Map<String, String> newConfig = new HashMap<String, String>();
    newConfig.putAll(proposedConfig);
    newConfig.put(PropertiesUtils.GOOGLE_CONNECTOR_NAME, name);
    newConfig.put(PropertiesUtils.GOOGLE_CONNECTOR_WORK_DIR, connectorDir
        .getPath());
    newConfig.put(PropertiesUtils.GOOGLE_WORK_DIR, Context.getInstance()
        .getCommonDirPath());

    // Validate the configuration.
    ConfigureResponse response =
        validateConfig(name, connectorDir, newTypeInfo, newConfig, locale);
    if (response != null) {
      return response;
    }

    // We have an apparently valid configuration. Create a connector instance
    // with that configuration.
    InstanceInfo newInstanceInfo =
        InstanceInfo.fromNewConfig(name, connectorDir, newTypeInfo, newConfig);
    if (newInstanceInfo == null) {
      // We don't expect this, because an InstantiatorException should have
      // been thrown, but just in case.
      throw new InstantiatorException("Failed to create connector " + name);
    }

    // Tell old connector instance to shut down, as it is being replaced.
    // TODO(strellis): Send cancel to the the old connector here?
    shutdownConnector(false);

    // Only after validateConfig and instantiation succeeds do we
    // save the new configuration to persistent store.
    newInstanceInfo.setConnectorConfig(newConfig);
    instanceInfo = newInstanceInfo;
    typeInfo = newTypeInfo;
    return null;
  }

  private static ConfigureResponse validateConfig(String name,
      File connectorDir, TypeInfo newTypeInfo, Map<String, String> config,
      Locale locale) throws InstantiatorException {
    ConnectorInstanceFactory factory =
        new ConnectorInstanceFactory(name, connectorDir, newTypeInfo, config);
    ConfigureResponse response;
    try {
      response =
          newTypeInfo.getConnectorType()
              .validateConfig(config, locale, factory);
    } catch (Exception e) {
      throw new InstantiatorException("Unexpected validateConfig failure.", e);
    } finally {
      factory.shutdown();
    }

    if (response != null) {
      // If validateConfig() returns a non-null response with an error message.
      // or populated config form, then consider it an invalid config that
      // needs to be corrected. Return the response so that the config form
      // may be redisplayed.
      if ((response.getMessage() != null)
          || (response.getFormSnippet() != null)) {
        LOGGER.warning("A rejected configuration for connector \"" + name
            + "\" was returned.");
        return response;
      }

      // If validateConfig() returns a response with no message or formSnippet,
      // but does include a configuration Map; then consider it a valid,
      // but possibly altered configuration and use it.
      if (response.getConfigData() != null) {
        LOGGER.config("A modified configuration for connector \"" + name
            + "\" was returned.");
        config.clear();
        config.putAll(response.getConfigData());
      }
    }
    return null;
  }

  private static File makeConnectorDirectoryFile(String name,
      TypeInfo typeInfo) {
    File connectorTypeDir = typeInfo.getConnectorTypeDir();
    return new File(connectorTypeDir, name);
  }

  private static File makeConnectorDirectory(String name, TypeInfo typeInfo)
      throws InstantiatorException {
    File connectorDir = makeConnectorDirectoryFile(name, typeInfo);
    if (connectorDir.exists()) {
      if (connectorDir.isDirectory()) {
        // we don't know why this directory already exists, but we're ok with it
        LOGGER.warning("Connector directory " + connectorDir.getAbsolutePath()
            + "; already exists for connector " + name);
      } else {
        throw new InstantiatorException("Existing file blocks creation of "
            + "connector directory at " + connectorDir.getAbsolutePath()
            + " for connector " + name);
      }
    } else {
      if (!connectorDir.mkdirs()) {
        throw new InstantiatorException("Can't create "
            + "connector directory at " + connectorDir.getAbsolutePath()
            + " for connector " + name);
      }
    }

    // If connectorInstance.xml file does not exist, copy it out of the
    // Connector's jar file.
    File configXml = new File(connectorDir, TypeInfo.CONNECTOR_INSTANCE_XML);
    if (!configXml.exists()) {
      try {
        InputStream in =
            typeInfo.getConnectorInstancePrototype().getInputStream();
        String config = StringUtils.streamToStringAndThrow(in);
        FileOutputStream out = new FileOutputStream(configXml);
        out.write(config.getBytes("UTF-8"));
        out.close();
      } catch (IOException ioe) {
        LOGGER.log(Level.WARNING, "Can't extract connectorInstance.xml "
            + " to connector directory at " + connectorDir.getAbsolutePath()
            + " for connector " + name, ioe);
      }
    }
    return connectorDir;
  }

  private static void removeConnectorDirectory(String name, File connectorDir,
      TypeInfo typeInfo) {
    // Remove the extracted connectorInstance.xml file, but only
    // if it is unmodified.
    // TODO: Remove this when fixing CM Issue 87?
    File configXml = new File(connectorDir, TypeInfo.CONNECTOR_INSTANCE_XML);
    if (configXml.exists()) {
      try {
        InputStream in1 =
            typeInfo.getConnectorInstancePrototype().getInputStream();
        FileInputStream in2 = new FileInputStream(configXml);
        String conf1 = StringUtils.streamToStringAndThrow(in1);
        String conf2 = StringUtils.streamToStringAndThrow(in2);
        if (conf1.equals(conf2) && !configXml.delete()) {
          LOGGER.log(Level.WARNING, "Can't delete connectorInstance.xml "
              + " from connector directory at "
              + connectorDir.getAbsolutePath() + " for connector " + name);
        }
      } catch (IOException ioe) {
        LOGGER.log(Level.WARNING, "Can't delete connectorInstance.xml "
            + " from connector directory at " + connectorDir.getAbsolutePath()
            + " for connector " + name, ioe);
      }
    }

    if (connectorDir.exists()) {
      if (!connectorDir.delete()) {
        LOGGER.warning("Can't delete connector directory "
            + connectorDir.getPath()
            + "; this connector may be difficult to delete.");
      }
    }
  }

  private static class ConnectorInstanceFactory implements ConnectorFactory {
    final String connectorName;
    final File connectorDir;
    final TypeInfo typeInfo;
    final Map<String, String> origConfig;
    final List<InstanceInfo> connectors;

    /**
     * Constructor takes the items needed by <code>InstanceInfo</code>, but not
     * provided via <code>makeConnector</code>.
     *
     * @param connectorName the name of this connector instance.
     * @param connectorDir the directory containing the connector prototype.
     * @param typeInfo the connector type.
     * @param config the configuration provided to <code>validateConfig</code>.
     */
    public ConnectorInstanceFactory(String connectorName, File connectorDir,
        TypeInfo typeInfo, Map<String, String> config) {
      this.connectorName = connectorName;
      this.connectorDir = connectorDir;
      this.typeInfo = typeInfo;
      this.origConfig = config;
      this.connectors = new ArrayList<InstanceInfo>();
    }

    /**
     * Create an instance of this connector based upon the supplied
     * configuration data. If the supplied config <code>Map</code> is
     * <code>null</code>, use the original configuration.
     *
     * @see com.google.enterprise.connector.spi.ConnectorFactory#makeConnector(Map)
     */
    public Connector makeConnector(Map<String, String> config)
        throws RepositoryException {
      try {
        InstanceInfo info =
            InstanceInfo.fromNewConfig(connectorName, connectorDir, typeInfo,
                ((config == null) ? origConfig : config));
        if (info == null) {
          return null;
        }
        connectors.add(info);
        return info.getConnector();
      } catch (InstantiatorException e) {
        throw new RepositoryException(
            "ConnectorFactory failed to make connector.", e);
      }
    }

    /**
     * Shutdown any connector instances created by the factory.
     */
    private void shutdown() {
      List<InstanceInfo> connectorList;
      synchronized (this) {
        connectorList = connectors;
      }
      if (connectorList != null) {
        for (InstanceInfo info : connectorList) {
          Connector connector = info.getConnector();
          if (connector instanceof ConnectorShutdownAware) {
            try {
              ((ConnectorShutdownAware) connector).shutdown();
            } catch (Exception e) {
              LOGGER.log(Level.WARNING, "Failed to shutdown connector "
                  + info.getName() + " created by validateConfig", e);
            }
          }
        }
      }
    }
  }
}