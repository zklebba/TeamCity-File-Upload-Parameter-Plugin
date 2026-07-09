package io.github.zklebba.teamcity.fileparameter.agent;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileParameterDiagnostics;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildAgentConfiguration;
import jetbrains.buildServer.agent.BuildRunnerContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class FileParameterAgentService {
  private static final Logger LOG = Logger.getLogger(FileParameterAgentService.class.getName());
  private final BuildAgentConfiguration configuration;
  private final Map<String, File> filesByToken = new HashMap<String, File>();
  private AgentRunningBuild runningBuild;

  public FileParameterAgentService(BuildAgentConfiguration configuration) {
    this.configuration = configuration;
  }

  public synchronized String resolve(FileUploadMarker marker, String parameterName) throws IOException {
    debug("resolve requested: parameterName=" + parameterName + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", fileName=" + marker.getFileName() + ", runningBuildPresent=" + (runningBuild != null));
    if (runningBuild == null) {
      throw new IOException("No running TeamCity build is available for file parameter download");
    }
    File existing = filesByToken.get(marker.getToken());
    if (existing != null && existing.isFile()) {
      debug("resolve cache hit: parameterName=" + parameterName + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", path=" + existing.getAbsolutePath());
      return existing.getAbsolutePath();
    }

    debug("resolve cache miss: parameterName=" + parameterName + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()));
    File file = download(marker, parameterName);
    filesByToken.put(marker.getToken(), file);
    debug("resolve completed: parameterName=" + parameterName + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", path=" + file.getAbsolutePath());
    return file.getAbsolutePath();
  }

  public synchronized void cleanup() {
    debug("cleanup start: cachedFiles=" + filesByToken.size());
    for (File file : filesByToken.values()) {
      if (file.isFile()) {
        debug("cleanup deleting file: " + file.getAbsolutePath());
        file.delete();
      }
    }
    filesByToken.clear();
    runningBuild = null;
    debug("cleanup complete");
  }

  public synchronized void buildStarted(AgentRunningBuild build) {
    runningBuild = build;
    debug("buildStarted: buildId=" + build.getBuildId() + ", buildTypeId=" + build.getBuildTypeId() + ", buildTempDirectory=" + configuration.getBuildTempDirectory().getAbsolutePath() + ", serverUrl=" + configuration.getServerUrl() + ", hasAgentAuthorizationToken=" + (configuration.getAuthorizationToken() != null));
  }

  public synchronized void beforeRunnerStart(BuildRunnerContext runner) {
    runningBuild = runner.getBuild();
    debug("beforeRunnerStart: runnerId=" + runner.getId() + ", runnerType=" + runner.getRunType() + ", runnerName=" + runner.getName() + ", configParameters=" + runner.getConfigParameters().size() + ", runnerParameters=" + runner.getRunnerParameters().size());

    Map<String, String> markerParameterNames = new HashMap<String, String>();
    resolveExactConfigMarkers(runner.getBuildParameters().getAllParameters(), runner, markerParameterNames, "build");
    resolveExactConfigMarkers(runner.getConfigParameters(), runner, markerParameterNames, "runnerConfig");

    for (Map.Entry<String, String> entry : runner.getRunnerParameters().entrySet()) {
      String resolved = replaceMarkers(entry.getValue(), markerParameterNames, entry.getKey());
      if (entry.getValue() != null && !entry.getValue().equals(resolved)) {
        runner.addRunnerParameter(entry.getKey(), resolved);
        debug("runner parameter markers replaced: runnerParameter=" + entry.getKey() + ", originalKind=" + FileParameterDiagnostics.valueKind(entry.getValue()));
      }
    }
  }

  public synchronized void reportFailure(String message, Throwable cause) {
    if (runningBuild != null) {
      runningBuild.getBuildLogger().buildFailureDescription(message);
      runningBuild.getBuildLogger().error(message);
      if (cause != null) {
        runningBuild.getBuildLogger().exception(cause);
      }
    }
  }

  private void resolveExactConfigMarkers(Map<String, String> parameters, BuildRunnerContext runner, Map<String, String> markerParameterNames, String source) {
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      FileUploadMarker marker = FileUploadMarker.parse(entry.getValue());
      if (marker == null) {
        continue;
      }

      markerParameterNames.put(marker.getToken(), entry.getKey());
      try {
        String path = resolve(marker, entry.getKey());
        runner.addConfigParameter(entry.getKey(), path);
        debug("config parameter marker resolved: source=" + source + ", parameterName=" + entry.getKey() + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", path=" + path);
      } catch (IOException e) {
        String message = "Cannot download TeamCity File parameter '" + entry.getKey() + "': " + e.getMessage();
        reportFailure(message, e);
        throw new IllegalStateException(message, e);
      }
    }
  }

  private String replaceMarkers(String value, Map<String, String> markerParameterNames, String fallbackParameterName) {
    if (value == null || value.indexOf(FileParameterConstants.MARKER_PREFIX) < 0) {
      return value;
    }

    StringBuilder result = new StringBuilder(value.length());
    int position = 0;
    while (position < value.length()) {
      int markerStart = value.indexOf(FileParameterConstants.MARKER_PREFIX, position);
      if (markerStart < 0) {
        result.append(value.substring(position));
        break;
      }

      result.append(value, position, markerStart);
      int markerEnd = markerEnd(value, markerStart);
      String markerText = value.substring(markerStart, markerEnd);
      FileUploadMarker marker = FileUploadMarker.parse(markerText);
      if (marker == null) {
        result.append(markerText);
      } else {
        String parameterName = markerParameterNames.containsKey(marker.getToken()) ? markerParameterNames.get(marker.getToken()) : fallbackParameterName;
        try {
          result.append(resolve(marker, parameterName));
          debug("embedded marker resolved: runnerParameter=" + fallbackParameterName + ", parameterName=" + parameterName + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()));
        } catch (IOException e) {
          String message = "Cannot download TeamCity File parameter '" + parameterName + "': " + e.getMessage();
          reportFailure(message, e);
          throw new IllegalStateException(message, e);
        }
      }
      position = markerEnd;
    }

    return result.toString();
  }

  private static int markerEnd(String value, int markerStart) {
    int index = markerStart;
    while (index < value.length()) {
      char c = value.charAt(index);
      if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '<' || c == '>') {
        break;
      }
      index++;
    }
    return index;
  }

  private File download(FileUploadMarker marker, String parameterName) throws IOException {
    AgentRunningBuild build = runningBuild;
    if (build == null) {
      throw new IOException("No running TeamCity build is available for file parameter download");
    }
    File directory = new File(configuration.getBuildTempDirectory(), "file-parameters");
    debug("download preparing directory: " + directory.getAbsolutePath());
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException("Cannot create " + directory.getAbsolutePath());
    }

    File target = uniqueTarget(directory, marker.getFileName());
    URL url = new URL(trimTrailingSlash(configuration.getServerUrl()) + FileParameterConstants.DOWNLOAD_PATH + "?token=" + encode(marker.getToken()));
    debug("download opening connection: url=" + url + ", parameterName=" + parameterName + ", buildId=" + build.getBuildId() + ", buildTypeId=" + build.getBuildTypeId() + ", target=" + target.getAbsolutePath() + ", hasAgentAuthorizationToken=" + (configuration.getAuthorizationToken() != null) + ", hasBuildAccessCredentials=" + hasBuildAccessCredentials(build));
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(configuration.getServerConnectionTimeout());
    connection.setReadTimeout(configuration.getServerConnectionTimeout());
    connection.setRequestProperty("User-Agent", "TeamCity File Parameter Agent Plugin");
    connection.setRequestProperty(FileParameterConstants.HEADER_FILE_SECRET, marker.getSecret());
    connection.setRequestProperty(FileParameterConstants.HEADER_BUILD_ID, Long.toString(build.getBuildId()));
    connection.setRequestProperty(FileParameterConstants.HEADER_BUILD_TYPE_ID, build.getBuildTypeId());
    if (build.getAccessCode() != null && build.getAccessCode().length() > 0) {
      connection.setRequestProperty(FileParameterConstants.HEADER_BUILD_ACCESS_CODE, build.getAccessCode());
    }
    if (parameterName != null) {
      connection.setRequestProperty(FileParameterConstants.HEADER_FILE_PARAMETER_NAME, parameterName);
    }
    if (hasBuildAccessCredentials(build)) {
      connection.setRequestProperty("Authorization", basicAuth(build.getAccessUser(), build.getAccessCode()));
    }
    if (configuration.getAuthorizationToken() != null) {
      connection.setRequestProperty(FileParameterConstants.HEADER_AGENT_AUTHORIZATION, configuration.getAuthorizationToken());
    }

    int status = connection.getResponseCode();
    debug("download response: status=" + status + ", contentLength=" + connection.getContentLengthLong() + ", fileNameHeader=" + connection.getHeaderField(FileParameterConstants.HEADER_FILE_NAME));
    if (status < 200 || status >= 300) {
      throw new IOException("server returned HTTP " + status);
    }

    boolean success = false;
    try {
      copy(connection.getInputStream(), new FileOutputStream(target));
      success = true;
      debug("download copied file: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", target=" + target.getAbsolutePath() + ", length=" + target.length());
      return target;
    } finally {
      if (!success && target.isFile()) {
        debug("download failed, deleting partial target: " + target.getAbsolutePath() + ", length=" + target.length());
        target.delete();
      }
    }
  }

  private static File uniqueTarget(File directory, String fileName) {
    String safeName = fileName == null || fileName.length() == 0 ? "upload.bin" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    File candidate = new File(directory, safeName);
    int index = 1;
    while (candidate.exists()) {
      candidate = new File(directory, index + "-" + safeName);
      index++;
    }
    return candidate;
  }

  private static void copy(InputStream input, FileOutputStream output) throws IOException {
    try {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    } finally {
      input.close();
      output.close();
    }
  }

  private static String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static String encode(String value) throws IOException {
    return URLEncoder.encode(value, "UTF-8");
  }

  private static boolean hasBuildAccessCredentials(AgentRunningBuild build) {
    return build.getAccessUser() != null && build.getAccessUser().length() > 0 && build.getAccessCode() != null && build.getAccessCode().length() > 0;
  }

  private static String basicAuth(String user, String password) {
    String credentials = user + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  private void debug(String message) {
    FileParameterDiagnostics.debug(LOG, message);
    AgentRunningBuild build = runningBuild;
    if (FileParameterDiagnostics.enabled() && build != null) {
      build.getBuildLogger().debug("[TeamCity File Parameter debug] " + message);
    }
  }
}
