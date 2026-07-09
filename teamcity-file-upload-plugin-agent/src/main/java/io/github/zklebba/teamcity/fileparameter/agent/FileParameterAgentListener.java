package io.github.zklebba.teamcity.fileparameter.agent;

import jetbrains.buildServer.agent.AgentLifeCycleAdapter;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildFinishedStatus;
import jetbrains.buildServer.agent.BuildRunnerContext;
import jetbrains.buildServer.agent.AgentLifeCycleListener;
import jetbrains.buildServer.util.EventDispatcher;

public class FileParameterAgentListener extends AgentLifeCycleAdapter {
  private final FileParameterAgentService service;

  public FileParameterAgentListener(FileParameterAgentService service, EventDispatcher<AgentLifeCycleListener> eventDispatcher) {
    this.service = service;
    eventDispatcher.addListener(this);
  }

  @Override
  public void buildStarted(AgentRunningBuild build) {
    service.buildStarted(build);
  }

  @Override
  public void beforeRunnerStart(BuildRunnerContext runner) {
    service.beforeRunnerStart(runner);
  }

  @Override
  public void buildFinished(AgentRunningBuild build, BuildFinishedStatus buildStatus) {
    service.cleanup();
  }
}
