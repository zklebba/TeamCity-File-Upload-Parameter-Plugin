package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileParameterDiagnostics;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.BuildAgentManager;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.SBuildAgent;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

public class FileDownloadController extends BaseController {
  private static final Logger LOG = Logger.getLogger(FileDownloadController.class.getName());
  private final FileUploadStorage storage;
  private final BuildAgentManager agentManager;

  public FileDownloadController(WebControllerManager manager, ServerPaths serverPaths, BuildAgentManager agentManager) {
    this.storage = FileUploadStorage.getInstance(serverPaths);
    this.agentManager = agentManager;
    if (FileParameterRegistration.claim(FileParameterConstants.DOWNLOAD_PATH)) {
      manager.registerController(FileParameterConstants.DOWNLOAD_PATH, this);
      LOG.info("TeamCity File Parameter: registered download controller at " + FileParameterConstants.DOWNLOAD_PATH);
    } else {
      LOG.warning("TeamCity File Parameter: skipped duplicate download controller registration at " + FileParameterConstants.DOWNLOAD_PATH);
    }
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    FileParameterDiagnostics.debug(LOG, "download request: method=" + request.getMethod() + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(request.getParameter("token")) + ", buildIdHeader=" + request.getHeader(FileParameterConstants.HEADER_BUILD_ID) + ", parameterNameHeader=" + request.getHeader(FileParameterConstants.HEADER_FILE_PARAMETER_NAME) + ", hasSecretHeader=" + (request.getHeader(FileParameterConstants.HEADER_FILE_SECRET) != null));
    if (!"GET".equalsIgnoreCase(request.getMethod())) {
      FileParameterDiagnostics.debug(LOG, "download rejected: invalid method " + request.getMethod());
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return null;
    }

    long buildId = buildId(request);
    SBuildAgent agent = authorizedAgent(request, buildId);
    if (agent == null) {
      FileParameterDiagnostics.debug(LOG, "download rejected: no authorized agent matched request token");
      LOG.warning("TeamCity File Parameter download rejected: no authorized agent matched request. buildId=" + buildId + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(request.getParameter("token")) + ", hasAgentToken=" + (request.getHeader(FileParameterConstants.HEADER_AGENT_AUTHORIZATION) != null) + ", hasBuildAccessCode=" + (request.getHeader(FileParameterConstants.HEADER_BUILD_ACCESS_CODE) != null));
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Authorized TeamCity agent token is required");
      return null;
    }
    SRunningBuild runningBuild = agent.getRunningBuild();
    if (runningBuild == null || buildId < 0 || runningBuild.getBuildId() != buildId) {
      FileParameterDiagnostics.debug(LOG, "download rejected: agent not running requested build, agentId=" + agent.getId() + ", requestedBuildId=" + buildId + ", runningBuildId=" + (runningBuild == null ? "<none>" : runningBuild.getBuildId()));
      LOG.warning("TeamCity File Parameter download rejected: agent is not running requested build. agentId=" + agent.getId() + ", requestedBuildId=" + buildId + ", runningBuildId=" + (runningBuild == null ? "<none>" : runningBuild.getBuildId()));
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "The authorized agent is not running the requested build");
      return null;
    }
    FileParameterDiagnostics.debug(LOG, "download agent/build matched: agentId=" + agent.getId() + ", buildId=" + runningBuild.getBuildId() + ", buildTypeId=" + runningBuild.getBuildTypeId() + ", ownerUserId=" + ownerUserId(runningBuild));

    String token = request.getParameter("token");
    String secret = request.getHeader(FileParameterConstants.HEADER_FILE_SECRET);
    String parameterName = request.getHeader(FileParameterConstants.HEADER_FILE_PARAMETER_NAME);
    if (!isFileParameter(runningBuild, parameterName)) {
      FileParameterDiagnostics.debug(LOG, "download rejected: parameter is not file type, parameterName=" + parameterName + ", buildTypePresent=" + (runningBuild.getBuildType() != null));
      LOG.warning("TeamCity File Parameter download rejected: parameter is not File type and does not contain an upload marker. buildId=" + runningBuild.getBuildId() + ", parameterName=" + parameterName + ", buildTypePresent=" + (runningBuild.getBuildType() != null) + ", raw=" + FileParameterDiagnostics.parameterMapSummary(runningBuild.getBuildOwnParameters(), parameterName) + ", resolved=" + FileParameterDiagnostics.parameterMapSummary(runningBuild.getParametersProvider().getAll(), parameterName));
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "The requested parameter is not a File parameter in this build");
      return null;
    }
    FileParameterDiagnostics.debug(LOG, "download parameter accepted: parameterName=" + parameterName + ", raw=" + FileParameterDiagnostics.parameterMapSummary(runningBuild.getBuildOwnParameters(), parameterName) + ", resolved=" + FileParameterDiagnostics.parameterMapSummary(runningBuild.getParametersProvider().getAll(), parameterName));

    String storageBuildTypeId = storageBuildTypeId(runningBuild);
    FileParameterDiagnostics.debug(LOG, "download storage build type id resolved: runningBuildTypeId=" + runningBuild.getBuildTypeId() + ", storageBuildTypeId=" + storageBuildTypeId);
    FileUploadRecord record = storage.startDownload(token, secret, runningBuild.getBuildId(), agent.getId(), ownerUserId(runningBuild), storageBuildTypeId, parameterName, runningBuild.getBuildOwnParameters(), runningBuild.getParametersProvider().getAll());
    if (record == null) {
      FileParameterDiagnostics.debug(LOG, "download rejected by storage: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token) + ", buildId=" + runningBuild.getBuildId() + ", agentId=" + agent.getId() + ", parameterName=" + parameterName);
      response.sendError(HttpServletResponse.SC_NOT_FOUND, "Upload token was not found, expired, already used, not assigned to this build, or is currently being downloaded");
      return null;
    }

    boolean success = false;
    try {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType("application/octet-stream");
      response.setHeader(FileParameterConstants.HEADER_FILE_NAME, record.getFileName());
      response.setContentLengthLong(record.getSize());
      copy(new FileInputStream(record.getFile()), response.getOutputStream());
      success = true;
      FileParameterDiagnostics.debug(LOG, "download streamed successfully: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken()) + ", fileName=" + record.getFileName() + ", size=" + record.getSize() + ", buildId=" + runningBuild.getBuildId() + ", agentId=" + agent.getId());
    } finally {
      if (success) {
        storage.completeDownload(record.getToken());
      } else {
        FileParameterDiagnostics.debug(LOG, "download stream failed before completion: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken()));
        storage.failDownload(record.getToken());
      }
    }
    return null;
  }

  private SBuildAgent authorizedAgent(HttpServletRequest request, long buildId) {
    String token = request.getHeader(FileParameterConstants.HEADER_AGENT_AUTHORIZATION);
    if ((token == null || token.length() == 0) && request.getHeader("Authorization") != null) {
      String authorization = request.getHeader("Authorization");
      if (authorization.startsWith("Bearer ")) {
        token = authorization.substring("Bearer ".length());
      }
    }
    if (token == null || token.length() == 0) {
      return null;
    }

    for (SBuildAgent agent : agentManager.getRegisteredAgents(true)) {
      String agentToken = agent.getAuthorizationToken();
      if (agent.isAuthorized() && constantTimeEquals(agentToken, token)) {
        FileParameterDiagnostics.debug(LOG, "authorized agent matched: agentId=" + agent.getId() + ", name=" + agent.getName());
        return agent;
      }
    }

    SBuildAgent buildAgent = agentRunningBuild(request, buildId);
    if (buildAgent != null) {
      FileParameterDiagnostics.debug(LOG, "authorized agent matched by build access code: agentId=" + buildAgent.getId() + ", name=" + buildAgent.getName() + ", buildId=" + buildId);
      return buildAgent;
    }
    return null;
  }

  private SBuildAgent agentRunningBuild(HttpServletRequest request, long buildId) {
    String buildAccessCode = request.getHeader(FileParameterConstants.HEADER_BUILD_ACCESS_CODE);
    if (buildId < 0 || buildAccessCode == null || buildAccessCode.length() == 0) {
      return null;
    }
    for (SBuildAgent agent : agentManager.getRegisteredAgents(true)) {
      SRunningBuild runningBuild = agent.getRunningBuild();
      if (agent.isAuthorized() && runningBuild != null && runningBuild.getBuildId() == buildId && constantTimeEquals(runningBuild.getAgentAccessCode(), buildAccessCode)) {
        return agent;
      }
    }
    return null;
  }

  private long buildId(HttpServletRequest request) {
    try {
      return Long.parseLong(request.getHeader(FileParameterConstants.HEADER_BUILD_ID));
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  private boolean isFileParameter(SRunningBuild runningBuild, String parameterName) {
    if (parameterName == null || parameterName.trim().isEmpty()) {
      return false;
    }

    if (hasUploadMarker(runningBuild.getBuildOwnParameters().get(parameterName)) || hasUploadMarker(runningBuild.getParametersProvider().getAll().get(parameterName))) {
      return true;
    }

    if (runningBuild.getBuildType() != null) {
      for (Parameter parameter : runningBuild.getBuildType().getBuildParametersCollection()) {
        if (parameterName.equals(parameter.getName()) && parameter.getControlDescription() != null && FileParameterConstants.PARAMETER_TYPE.equals(parameter.getControlDescription().getParameterType())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasUploadMarker(String value) {
    return value != null && value.startsWith(FileParameterConstants.MARKER_PREFIX);
  }

  private long ownerUserId(SRunningBuild runningBuild) {
    SUser owner = runningBuild.getOwner();
    return owner == null ? -1L : owner.getId();
  }

  private String storageBuildTypeId(SRunningBuild runningBuild) {
    if (runningBuild.getBuildType() != null) {
      return runningBuild.getBuildType().getBuildTypeId();
    }
    return runningBuild.getBuildTypeId();
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    try {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    } finally {
      input.close();
    }
  }

  private static boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    int diff = left.length() ^ right.length();
    int max = Math.max(left.length(), right.length());
    for (int i = 0; i < max; i++) {
      char l = i < left.length() ? left.charAt(i) : 0;
      char r = i < right.length() ? right.charAt(i) : 0;
      diff |= l ^ r;
    }
    return diff == 0;
  }
}
