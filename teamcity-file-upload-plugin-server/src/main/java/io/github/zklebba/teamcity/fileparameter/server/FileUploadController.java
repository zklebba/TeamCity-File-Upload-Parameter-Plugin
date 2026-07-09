package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileParameterDiagnostics;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.auth.AuthorityHolder;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.auth.SecurityContext;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.logging.Logger;

public class FileUploadController extends BaseController {
  private static final Logger LOG = Logger.getLogger(FileUploadController.class.getName());
  private final FileUploadStorage storage;
  private final SecurityContext securityContext;
  private final ProjectManager projectManager;

  public FileUploadController(WebControllerManager manager, ServerPaths serverPaths, SecurityContext securityContext, ProjectManager projectManager) {
    this.storage = FileUploadStorage.getInstance(serverPaths);
    this.securityContext = securityContext;
    this.projectManager = projectManager;
    if (FileParameterRegistration.claim(FileParameterConstants.UPLOAD_PATH)) {
      manager.registerController(FileParameterConstants.UPLOAD_PATH, this);
      LOG.info("TeamCity File Parameter: registered upload controller at " + FileParameterConstants.UPLOAD_PATH);
    } else {
      LOG.warning("TeamCity File Parameter: skipped duplicate upload controller registration at " + FileParameterConstants.UPLOAD_PATH);
    }
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    handleUpload(request, response);
    return null;
  }

  private void handleUpload(HttpServletRequest request, HttpServletResponse response) throws Exception {
    FileParameterDiagnostics.debug(LOG, "upload request: method=" + request.getMethod() + ", contentLength=" + request.getContentLengthLong() + ", buildTypeHeader=" + request.getHeader(FileParameterConstants.HEADER_BUILD_TYPE_ID) + ", fileNameHeaderPresent=" + (request.getHeader(FileParameterConstants.HEADER_FILE_NAME) != null) + ", csrfHeaderPresent=" + (request.getHeader("X-TC-CSRF-Token") != null) + ", csrfParamPresent=" + (request.getParameter("tc-csrf-token") != null));
    if (!"POST".equalsIgnoreCase(request.getMethod())) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: invalid method " + request.getMethod());
      response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
      return;
    }

    if (!hasValidCsrfToken(request)) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: csrf token mismatch");
      sendPlainError(response, HttpServletResponse.SC_FORBIDDEN, "Invalid CSRF token");
      return;
    }

    String fileName = request.getHeader(FileParameterConstants.HEADER_FILE_NAME);
    if (fileName == null || fileName.trim().isEmpty()) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: missing file name header");
      sendPlainError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing " + FileParameterConstants.HEADER_FILE_NAME + " header");
      return;
    }
    String parameterName = request.getHeader(FileParameterConstants.HEADER_FILE_PARAMETER_NAME);
    if (parameterName == null || parameterName.trim().isEmpty()) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: missing parameter name header");
      sendPlainError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing " + FileParameterConstants.HEADER_FILE_PARAMETER_NAME + " header");
      return;
    }

    SBuildType buildType = resolveBuildType(request.getHeader(FileParameterConstants.HEADER_BUILD_TYPE_ID));
    if (buildType == null) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: invalid build type header " + request.getHeader(FileParameterConstants.HEADER_BUILD_TYPE_ID));
      sendPlainError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing or invalid " + FileParameterConstants.HEADER_BUILD_TYPE_ID + " header");
      return;
    }
    FileParameterDiagnostics.debug(LOG, "upload build type resolved: internalId=" + buildType.getBuildTypeId() + ", externalId=" + buildType.getExternalId() + ", projectId=" + buildType.getProjectId());
    Parameter parameter = fileParameter(buildType, parameterName);
    if (parameter == null) {
      LOG.warning("TeamCity File Parameter upload rejected: parameter definition not found or is not a File upload parameter. buildTypeId=" + buildType.getBuildTypeId() + ", parameterName=" + parameterName);
      sendPlainError(response, HttpServletResponse.SC_BAD_REQUEST, "Parameter is not configured as a File upload parameter");
      return;
    }
    FileParameterOptions options = FileParameterOptions.from(parameter.getControlDescription());

    User user = currentUser();
    if (user == null || !user.isPermissionGrantedForProject(buildType.getProject().getProjectId(), Permission.RUN_BUILD)) {
      FileParameterDiagnostics.debug(LOG, "upload rejected: user missing permission, userId=" + (user == null ? "<none>" : user.getId()) + ", projectId=" + buildType.getProject().getProjectId());
      sendPlainError(response, HttpServletResponse.SC_FORBIDDEN, "RUN_BUILD permission is required for this build configuration");
      return;
    }

    FileUploadRecord record;
    try {
      record = storage.save(fileName, request.getContentLengthLong(), request.getInputStream(), user.getId(), buildType.getBuildTypeId(), options);
    } catch (java.io.IOException e) {
      FileParameterDiagnostics.debug(LOG, "upload rejected by storage validation: " + e.getMessage());
      sendPlainError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return;
    }
    String marker = FileUploadMarker.create(record.getToken(), record.getSecret(), record.getFileName());
    FileParameterDiagnostics.debug(LOG, "upload accepted: userId=" + user.getId() + ", buildTypeId=" + buildType.getBuildTypeId() + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken()) + ", fileName=" + record.getFileName() + ", size=" + record.getSize() + ", expiresAtMillis=" + record.getExpiresAtMillis());

    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"value\":" + json(marker) + ",\"fileName\":" + json(record.getFileName()) + ",\"size\":" + record.getSize() + "}");
  }

  private User currentUser() {
    AuthorityHolder holder = securityContext.getAuthorityHolder();
    return holder == null ? null : holder.getAssociatedUser();
  }

  private SBuildType resolveBuildType(String buildTypeId) {
    if (buildTypeId == null || buildTypeId.trim().isEmpty()) {
      return null;
    }
    try {
      buildTypeId = normalizeBuildTypeId(buildTypeId);
      FileParameterDiagnostics.debug(LOG, "resolve upload build type: normalized=" + buildTypeId);
      SBuildType buildType = projectManager.findBuildTypeById(buildTypeId);
      if (buildType != null) {
        FileParameterDiagnostics.debug(LOG, "resolve upload build type matched by internal id: " + buildType.getBuildTypeId());
        return buildType;
      }
      buildType = projectManager.findBuildTypeByExternalId(buildTypeId);
      if (buildType != null) {
        FileParameterDiagnostics.debug(LOG, "resolve upload build type matched by external id: " + buildType.getExternalId());
        return buildType;
      }
      buildType = projectManager.findBuildTypeByConfigId(buildTypeId);
      if (buildType != null) {
        FileParameterDiagnostics.debug(LOG, "resolve upload build type matched by config id: " + buildTypeId);
      }
      return buildType;
    } catch (Exception e) {
      FileParameterDiagnostics.debug(LOG, "resolve upload build type failed: input=" + buildTypeId + ", error=" + e.getClass().getName() + ": " + e.getMessage());
      return null;
    }
  }

  private static Parameter fileParameter(SBuildType buildType, String parameterName) {
    for (Parameter parameter : buildType.getParametersCollection()) {
      if (isFileParameter(parameter, parameterName)) {
        return parameter;
      }
    }
    for (Parameter parameter : buildType.getConfigParametersCollection()) {
      if (isFileParameter(parameter, parameterName)) {
        return parameter;
      }
    }
    for (Parameter parameter : buildType.getBuildParametersCollection()) {
      if (isFileParameter(parameter, parameterName)) {
        return parameter;
      }
    }
    return null;
  }

  private static boolean isFileParameter(Parameter parameter, String parameterName) {
    return parameterName.equals(parameter.getName())
        && parameter.getControlDescription() != null
        && FileParameterConstants.PARAMETER_TYPE.equals(parameter.getControlDescription().getParameterType());
  }

  private static String normalizeBuildTypeId(String buildTypeId) {
    String trimmed = buildTypeId.trim();
    return trimmed.startsWith("buildType:") ? trimmed.substring("buildType:".length()) : trimmed;
  }

  private boolean hasValidCsrfToken(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    Object expected = session == null ? null : session.getAttribute("tc-csrf-token");
    String expectedToken = expected == null ? null : String.valueOf(expected);
    String actualToken = request.getHeader("X-TC-CSRF-Token");
    if (actualToken == null || actualToken.isEmpty()) {
      actualToken = request.getParameter("tc-csrf-token");
    }
    FileParameterDiagnostics.debug(LOG, "upload csrf check: hasSession=" + (session != null) + ", expectedPresent=" + (expectedToken != null && !expectedToken.isEmpty()) + ", actualPresent=" + (actualToken != null && !actualToken.isEmpty()));
    return expectedToken != null && !expectedToken.isEmpty() && expectedToken.equals(actualToken);
  }

  private static void sendPlainError(HttpServletResponse response, int status, String message) throws java.io.IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(message == null ? "" : message);
  }

  private static String json(String value) {
    if (value == null) {
      return "null";
    }
    StringBuilder result = new StringBuilder(value.length() + 2);
    result.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          result.append("\\\"");
          break;
        case '\\':
          result.append("\\\\");
          break;
        case '\b':
          result.append("\\b");
          break;
        case '\f':
          result.append("\\f");
          break;
        case '\n':
          result.append("\\n");
          break;
        case '\r':
          result.append("\\r");
          break;
        case '\t':
          result.append("\\t");
          break;
        default:
          if (c < 0x20) {
            result.append(String.format("\\u%04x", (int) c));
          } else {
            result.append(c);
          }
      }
    }
    result.append('"');
    return result.toString();
  }
}
