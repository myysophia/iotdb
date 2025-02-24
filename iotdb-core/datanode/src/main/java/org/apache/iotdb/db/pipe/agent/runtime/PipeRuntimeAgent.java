/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.pipe.agent.runtime;

import org.apache.iotdb.commons.consensus.index.impl.RecoverProgressIndex;
import org.apache.iotdb.commons.exception.StartupException;
import org.apache.iotdb.commons.exception.pipe.PipeRuntimeCriticalException;
import org.apache.iotdb.commons.exception.pipe.PipeRuntimeException;
import org.apache.iotdb.commons.pipe.config.PipeConfig;
import org.apache.iotdb.commons.pipe.task.meta.PipeTaskMeta;
import org.apache.iotdb.commons.service.IService;
import org.apache.iotdb.commons.service.ServiceType;
import org.apache.iotdb.commons.utils.TestOnly;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.pipe.agent.PipeAgent;
import org.apache.iotdb.db.pipe.progress.assigner.SimpleConsensusProgressIndexAssigner;
import org.apache.iotdb.db.pipe.resource.PipeHardlinkFileDirStartupCleaner;
import org.apache.iotdb.db.protocol.client.ConfigNodeClient;
import org.apache.iotdb.db.protocol.client.ConfigNodeClientManager;
import org.apache.iotdb.db.protocol.client.ConfigNodeInfo;
import org.apache.iotdb.db.queryengine.plan.planner.plan.node.write.InsertNode;
import org.apache.iotdb.db.service.ResourcesInformationHolder;
import org.apache.iotdb.db.storageengine.dataregion.tsfile.TsFileResource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class PipeRuntimeAgent implements IService {

  private static final Logger LOGGER = LoggerFactory.getLogger(PipeRuntimeAgent.class);
  private static final int DATA_NODE_ID = IoTDBDescriptor.getInstance().getConfig().getDataNodeId();

  private final AtomicBoolean isShutdown = new AtomicBoolean(false);
  private final AtomicReference<String> clusterId = new AtomicReference<>(null);

  private final SimpleConsensusProgressIndexAssigner simpleConsensusProgressIndexAssigner =
      new SimpleConsensusProgressIndexAssigner();

  private final PipePeriodicalJobExecutor pipePeriodicalJobExecutor =
      new PipePeriodicalJobExecutor();

  //////////////////////////// System Service Interface ////////////////////////////

  public synchronized void preparePipeResources(
      ResourcesInformationHolder resourcesInformationHolder) throws StartupException {
    // clean sender (connector) hardlink file dir
    PipeHardlinkFileDirStartupCleaner.clean();

    // clean receiver file dir
    PipeAgent.receiver().cleanPipeReceiverDirs();

    PipeAgentLauncher.launchPipePluginAgent(resourcesInformationHolder);
    simpleConsensusProgressIndexAssigner.start();
  }

  @Override
  public synchronized void start() throws StartupException {
    PipeConfig.getInstance().printAllConfigs();
    PipeAgentLauncher.launchPipeTaskAgent();

    registerPeriodicalJob(
        "PipeTaskAgent#restartAllStuckPipes",
        PipeAgent.task()::restartAllStuckPipes,
        PipeConfig.getInstance().getPipeStuckRestartIntervalSeconds());
    pipePeriodicalJobExecutor.start();

    isShutdown.set(false);
  }

  @Override
  public synchronized void stop() {
    if (isShutdown.get()) {
      return;
    }
    isShutdown.set(true);

    pipePeriodicalJobExecutor.stop();
    PipeAgent.task().dropAllPipeTasks();
  }

  public boolean isShutdown() {
    return isShutdown.get();
  }

  @Override
  public ServiceType getID() {
    return ServiceType.PIPE_RUNTIME_AGENT;
  }

  public String getClusterIdIfPossible() {
    if (clusterId.get() == null) {
      synchronized (clusterId) {
        if (clusterId.get() == null) {
          try (final ConfigNodeClient configNodeClient =
              ConfigNodeClientManager.getInstance().borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
            clusterId.set(configNodeClient.getClusterId().getClusterId());
          } catch (Exception e) {
            LOGGER.warn("Unable to get clusterId, because: {}", e.getMessage(), e);
          }
        }
      }
    }
    return clusterId.get();
  }

  ////////////////////// SimpleConsensus ProgressIndex Assigner //////////////////////

  public void assignSimpleProgressIndexIfNeeded(InsertNode insertNode) {
    simpleConsensusProgressIndexAssigner.assignIfNeeded(insertNode);
  }

  ////////////////////// Load ProgressIndex Assigner //////////////////////

  public void assignProgressIndexForTsFileLoad(TsFileResource tsFileResource) {
    // override the progress index of the tsfile resource, not to update the progress index
    tsFileResource.setProgressIndex(getNextProgressIndexForTsFileLoad());
  }

  public RecoverProgressIndex getNextProgressIndexForTsFileLoad() {
    return new RecoverProgressIndex(
        DATA_NODE_ID,
        simpleConsensusProgressIndexAssigner.getSimpleProgressIndexForTsFileRecovery());
  }

  ////////////////////// Recover ProgressIndex Assigner //////////////////////

  public void assignProgressIndexForTsFileRecovery(TsFileResource tsFileResource) {
    tsFileResource.updateProgressIndex(
        new RecoverProgressIndex(
            DATA_NODE_ID,
            simpleConsensusProgressIndexAssigner.getSimpleProgressIndexForTsFileRecovery()));
  }

  //////////////////////////// Runtime Exception Handlers ////////////////////////////

  public void report(PipeTaskMeta pipeTaskMeta, PipeRuntimeException pipeRuntimeException) {
    LOGGER.warn(
        "Report PipeRuntimeException to local PipeTaskMeta({}), exception message: {}",
        pipeTaskMeta,
        pipeRuntimeException.getMessage(),
        pipeRuntimeException);

    pipeTaskMeta.trackExceptionMessage(pipeRuntimeException);

    // Quick stop all pipes locally if critical exception occurs,
    // no need to wait for the next heartbeat cycle.
    if (pipeRuntimeException instanceof PipeRuntimeCriticalException) {
      // To avoid deadlock, we use a new thread to stop all pipes.
      CompletableFuture.runAsync(() -> PipeAgent.task().stopAllPipesWithCriticalException());
    }
  }

  /////////////////////////// Periodical Job Executor ///////////////////////////

  public void registerPeriodicalJob(String id, Runnable periodicalJob, long intervalInSeconds) {
    pipePeriodicalJobExecutor.register(id, periodicalJob, intervalInSeconds);
  }

  @TestOnly
  public void startPeriodicalJobExecutor() {
    pipePeriodicalJobExecutor.start();
  }

  @TestOnly
  public void stopPeriodicalJobExecutor() {
    pipePeriodicalJobExecutor.stop();
  }

  @TestOnly
  public void clearPeriodicalJobExecutor() {
    pipePeriodicalJobExecutor.clear();
  }
}
