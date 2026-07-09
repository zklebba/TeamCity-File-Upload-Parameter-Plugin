package io.github.zklebba.teamcity.fileparameter;

public final class FileParameterConstants {
  public static final String PARAMETER_TYPE = "file";
  public static final String MARKER_PREFIX = "tc-file-upload://";
  public static final String UPLOAD_PATH = "/fileParameterUpload.html";
  public static final String DOWNLOAD_PATH = "/fileParameterDownload.html";
  public static final String HEADER_FILE_NAME = "X-TeamCity-File-Name";
  public static final String HEADER_BUILD_TYPE_ID = "X-TeamCity-BuildType-Id";
  public static final String HEADER_BUILD_ID = "X-TeamCity-Build-Id";
  public static final String HEADER_AGENT_AUTHORIZATION = "X-TeamCity-Agent-Authorization";
  public static final String HEADER_BUILD_ACCESS_CODE = "X-TeamCity-Build-Access-Code";
  public static final String HEADER_FILE_SECRET = "X-TeamCity-File-Secret";
  public static final String HEADER_FILE_PARAMETER_NAME = "X-TeamCity-File-Parameter-Name";
  public static final String PARAMETER_OPTION_MAX_SIZE = "maxSize";
  public static final String PARAMETER_OPTION_MAX_SIZE_MB = "maxSizeMb";
  public static final String PARAMETER_OPTION_ALLOWED_TYPES = "allowedTypes";
  public static final String PROPERTY_MAX_UPLOAD_BYTES = "teamcity.fileParameter.maxUploadBytes";
  public static final String PROPERTY_SERVER_TTL_MINUTES = "teamcity.fileParameter.serverTtlMinutes";
  public static final String PROPERTY_DEBUG = "teamcity.fileParameter.debug";
  public static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
  public static final long DEFAULT_TTL_MINUTES = 120L;

  private FileParameterConstants() {
  }
}
