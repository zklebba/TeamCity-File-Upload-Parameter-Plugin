package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileParameterDiagnostics;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.serverSide.ServerPaths;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.logging.Logger;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class FileUploadStorage {
  private static final Logger LOG = Logger.getLogger(FileUploadStorage.class.getName());
  private static volatile FileUploadStorage instance;
  private final File rootDir;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, FileUploadRecord> records = new ConcurrentHashMap<String, FileUploadRecord>();

  public static FileUploadStorage getInstance(ServerPaths serverPaths) {
    FileUploadStorage local = instance;
    if (local == null) {
      synchronized (FileUploadStorage.class) {
        local = instance;
        if (local == null) {
          local = new FileUploadStorage(serverPaths);
          instance = local;
        }
      }
    }
    return local;
  }

  public FileUploadStorage(ServerPaths serverPaths) {
    this.rootDir = new File(serverPaths.getPluginDataDirectory(), "file-parameter-uploads");
    FileParameterDiagnostics.debug(LOG, "storage init: rootDir=" + rootDir.getAbsolutePath() + ", maxUploadBytes=" + Long.getLong(FileParameterConstants.PROPERTY_MAX_UPLOAD_BYTES, FileParameterConstants.DEFAULT_MAX_UPLOAD_BYTES) + ", ttlMinutes=" + Long.getLong(FileParameterConstants.PROPERTY_SERVER_TTL_MINUTES, FileParameterConstants.DEFAULT_TTL_MINUTES));
    loadRecords();
    cleanupExpired();
  }

  public synchronized FileUploadRecord save(String originalName, long declaredLength, InputStream input, long userId, String buildTypeId, FileParameterOptions options) throws IOException {
    cleanupExpired();
    long maxBytes = Long.getLong(FileParameterConstants.PROPERTY_MAX_UPLOAD_BYTES, FileParameterConstants.DEFAULT_MAX_UPLOAD_BYTES);
    long effectiveMaxBytes = effectiveMaxBytes(maxBytes, options);
    FileParameterDiagnostics.debug(LOG, "storage save start: originalName=" + originalName + ", declaredLength=" + declaredLength + ", userId=" + userId + ", buildTypeId=" + buildTypeId + ", maxBytes=" + maxBytes + ", parameterMaxBytes=" + (options == null ? 0L : options.getMaxSizeBytes()) + ", effectiveMaxBytes=" + effectiveMaxBytes + ", allowedTypes=" + (options == null ? "" : options.getAllowedTypes()));
    if (declaredLength > effectiveMaxBytes) {
      FileParameterDiagnostics.debug(LOG, "storage save rejected: declared length exceeds limit, declaredLength=" + declaredLength + ", maxBytes=" + effectiveMaxBytes);
      throw new IOException("File is larger than the configured limit of " + effectiveMaxBytes + " bytes");
    }

    ensureRootDir();
    String token = newToken();
    String secret = newToken();
    String safeName = sanitizeFileName(originalName);
    File target = dataFile(token);
    long written = copyLimited(input, target, effectiveMaxBytes);
    String detectedContentType = detectContentType(target, safeName);
    if (options != null && !options.isAllowed(safeName, detectedContentType)) {
      if (!target.delete() && target.isFile()) {
        LOG.warning("TeamCity File Parameter upload: failed to delete rejected upload file " + target.getAbsolutePath());
      }
      throw new IOException("File type is not allowed. Detected type: " + detectedContentType + ". Allowed types: " + options.getAllowedTypes());
    }
    long ttlMinutes = Long.getLong(FileParameterConstants.PROPERTY_SERVER_TTL_MINUTES, FileParameterConstants.DEFAULT_TTL_MINUTES);
    long expiresAt = System.currentTimeMillis() + ttlMinutes * 60L * 1000L;
    FileUploadRecord record = new FileUploadRecord(token, secret, hashSecret(token, secret), safeName, target, written, expiresAt, userId, buildTypeId, -1L, -1, false, false);
    persist(record);
    records.put(token, record);
    FileParameterDiagnostics.debug(LOG, "storage save complete: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token) + ", safeName=" + safeName + ", contentType=" + detectedContentType + ", file=" + target.getAbsolutePath() + ", written=" + written + ", expiresAtMillis=" + expiresAt);
    return record;
  }

  public synchronized FileUploadRecord startDownload(String token, String secret, long buildId, int agentId, long buildOwnerUserId, String buildTypeId, String parameterName, Map<String, String> rawBuildParameters, Map<String, String> resolvedBuildParameters) throws IOException {
    cleanupExpired();
    FileParameterDiagnostics.debug(LOG, "storage startDownload: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token) + ", hasSecret=" + (secret != null) + ", buildId=" + buildId + ", agentId=" + agentId + ", buildOwnerUserId=" + buildOwnerUserId + ", buildTypeId=" + buildTypeId + ", parameterName=" + parameterName + ", raw=" + FileParameterDiagnostics.parameterMapSummary(rawBuildParameters, parameterName) + ", resolved=" + FileParameterDiagnostics.parameterMapSummary(resolvedBuildParameters, parameterName));
    FileUploadRecord record = records.get(token);
    if (record == null) {
      warnStartDownloadRejected("token not found", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, null);
      return null;
    }
    if (record.isConsumed()) {
      warnStartDownloadRejected("record already consumed", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (record.isDownloading()) {
      warnStartDownloadRejected("record currently downloading", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (!constantTimeEquals(record.getSecretHash(), hashSecret(token, secret))) {
      warnStartDownloadRejected("secret hash mismatch", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (!record.getFile().isFile()) {
      warnStartDownloadRejected("data file missing", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (!equals(record.getBuildTypeId(), buildTypeId)) {
      warnStartDownloadRejected("build type mismatch", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (!ownerMatches(record, buildOwnerUserId)) {
      warnStartDownloadRejected("owner mismatch", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if (!buildContainsMarkerWhenPresent(record, secret, parameterName, rawBuildParameters, resolvedBuildParameters)) {
      warnStartDownloadRejected("marker not present or not matching", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }
    if ((record.getClaimedBuildId() >= 0 && record.getClaimedBuildId() != buildId) || (record.getClaimedAgentId() >= 0 && record.getClaimedAgentId() != agentId)) {
      warnStartDownloadRejected("existing claim mismatch", token, buildId, agentId, buildOwnerUserId, buildTypeId, parameterName, rawBuildParameters, resolvedBuildParameters, record);
      return null;
    }

    FileUploadRecord downloading = record.claimedBy(buildId, agentId).downloading(true);
    persist(downloading);
    records.put(token, downloading);
    FileParameterDiagnostics.debug(LOG, "storage startDownload accepted: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token) + ", buildId=" + buildId + ", agentId=" + agentId + ", size=" + downloading.getSize() + ", fileName=" + downloading.getFileName());
    return downloading;
  }

  public synchronized void failDownload(String token) throws IOException {
    FileUploadRecord record = records.get(token);
    if (record != null && record.isDownloading() && !record.isConsumed()) {
      FileUploadRecord unlocked = record.downloading(false);
      persist(unlocked);
      records.put(token, unlocked);
      FileParameterDiagnostics.debug(LOG, "storage failDownload unlocked token: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token));
    }
  }

  public synchronized void completeDownload(String token) {
    FileUploadRecord record = records.get(token);
    if (record != null) {
      records.put(token, record.consumed(true).downloading(false));
    }
    FileParameterDiagnostics.debug(LOG, "storage completeDownload removing token: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token));
    remove(token);
  }

  public synchronized void remove(String token) {
    FileUploadRecord record = records.remove(token);
    if (record != null && record.getFile().isFile()) {
      FileParameterDiagnostics.debug(LOG, "storage remove deleting data file: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token) + ", file=" + record.getFile().getAbsolutePath());
      record.getFile().delete();
    }
    File metadata = metadataFile(token);
    if (metadata.isFile()) {
      metadata.delete();
    }
  }

  public synchronized void cleanupExpired() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, FileUploadRecord> entry : records.entrySet()) {
      FileUploadRecord record = entry.getValue();
      if (record.getExpiresAtMillis() < now || !record.getFile().isFile()) {
        FileParameterDiagnostics.debug(LOG, "storage cleanup removing expired/missing record: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(entry.getKey()) + ", expiresAtMillis=" + record.getExpiresAtMillis() + ", fileExists=" + record.getFile().isFile());
        remove(entry.getKey());
      }
    }
  }

  private void loadRecords() {
    if (!rootDir.isDirectory()) {
      return;
    }
    File[] files = rootDir.listFiles();
    if (files == null) {
      return;
    }
    for (File file : files) {
      if (!file.getName().endsWith(".properties")) {
        continue;
      }
      try {
        FileUploadRecord record = read(file);
        if (record != null) {
          records.put(record.getToken(), record);
          FileParameterDiagnostics.debug(LOG, "storage loaded persisted record: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken()) + ", fileName=" + record.getFileName() + ", buildTypeId=" + record.getBuildTypeId() + ", claimedBuildId=" + record.getClaimedBuildId() + ", consumed=" + record.isConsumed());
        }
      } catch (IOException ignored) {
        FileParameterDiagnostics.debug(LOG, "storage failed to read metadata file: " + file.getAbsolutePath() + ", error=" + ignored.getMessage());
      }
    }
  }

  private static void warnStartDownloadRejected(String reason, String token, long buildId, int agentId, long buildOwnerUserId, String buildTypeId, String parameterName, Map<String, String> rawBuildParameters, Map<String, String> resolvedBuildParameters, FileUploadRecord record) {
    LOG.warning("TeamCity File Parameter storage download rejected: reason=" + reason
        + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(token)
        + ", buildId=" + buildId
        + ", agentId=" + agentId
        + ", buildOwnerUserId=" + buildOwnerUserId
        + ", requestBuildTypeId=" + buildTypeId
        + ", parameterName=" + parameterName
        + ", raw=" + FileParameterDiagnostics.parameterMapSummary(rawBuildParameters, parameterName)
        + ", resolved=" + FileParameterDiagnostics.parameterMapSummary(resolvedBuildParameters, parameterName)
        + ", record=" + recordSummary(record));
  }

  private static String recordSummary(FileUploadRecord record) {
    if (record == null) {
      return "<none>";
    }
    return "tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken())
        + ", buildTypeId=" + record.getBuildTypeId()
        + ", uploadUserId=" + record.getUserId()
        + ", claimedBuildId=" + record.getClaimedBuildId()
        + ", claimedAgentId=" + record.getClaimedAgentId()
        + ", consumed=" + record.isConsumed()
        + ", downloading=" + record.isDownloading()
        + ", fileExists=" + record.getFile().isFile()
        + ", expiresAtMillis=" + record.getExpiresAtMillis();
  }

  private FileUploadRecord read(File file) throws IOException {
    Properties properties = new Properties();
    FileInputStream input = new FileInputStream(file);
    try {
      properties.load(input);
    } finally {
      input.close();
    }
    String token = properties.getProperty("token");
    if (token == null || token.length() == 0) {
      return null;
    }
    File dataFile = dataFile(token);
    return new FileUploadRecord(
        token,
        null,
        properties.getProperty("secretHash", properties.getProperty("secret", "")),
        properties.getProperty("fileName", "upload.bin"),
        dataFile,
        longProperty(properties, "size", dataFile.length()),
        longProperty(properties, "expiresAtMillis", 0L),
        longProperty(properties, "userId", -1L),
        properties.getProperty("buildTypeId", ""),
        longProperty(properties, "claimedBuildId", -1L),
        intProperty(properties, "claimedAgentId", -1),
        Boolean.parseBoolean(properties.getProperty("consumed", "false")),
        false
    );
  }

  private void persist(FileUploadRecord record) throws IOException {
    ensureRootDir();
    Properties properties = new Properties();
    properties.setProperty("token", record.getToken());
    properties.setProperty("secretHash", record.getSecretHash());
    properties.setProperty("fileName", record.getFileName());
    properties.setProperty("size", Long.toString(record.getSize()));
    properties.setProperty("expiresAtMillis", Long.toString(record.getExpiresAtMillis()));
    properties.setProperty("userId", Long.toString(record.getUserId()));
    properties.setProperty("buildTypeId", record.getBuildTypeId());
    properties.setProperty("claimedBuildId", Long.toString(record.getClaimedBuildId()));
    properties.setProperty("claimedAgentId", Integer.toString(record.getClaimedAgentId()));
    properties.setProperty("consumed", Boolean.toString(record.isConsumed()));
    properties.setProperty("downloading", Boolean.toString(record.isDownloading()));
    FileOutputStream output = new FileOutputStream(metadataFile(record.getToken()));
    try {
      properties.store(output, "TeamCity file parameter upload metadata");
    } finally {
      output.close();
    }
  }

  private void ensureRootDir() throws IOException {
    if (!rootDir.isDirectory() && !rootDir.mkdirs()) {
      throw new IOException("Cannot create upload directory: " + rootDir.getAbsolutePath());
    }
  }

  private File dataFile(String token) {
    return new File(rootDir, token + ".bin");
  }

  private File metadataFile(String token) {
    return new File(rootDir, token + ".properties");
  }

  private String newToken() {
    byte[] bytes = new byte[24];
    random.nextBytes(bytes);
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
    }
    return result.toString();
  }

  private static long copyLimited(InputStream input, File target, long maxBytes) throws IOException {
    long written = 0L;
    boolean success = false;
    byte[] buffer = new byte[64 * 1024];
    FileOutputStream output = new FileOutputStream(target);
    try {
      int read;
      while ((read = input.read(buffer)) != -1) {
        written += read;
        if (written > maxBytes) {
          throw new IOException("File is larger than the configured limit of " + maxBytes + " bytes");
        }
        output.write(buffer, 0, read);
      }
      success = true;
      return written;
    } finally {
      output.close();
      if (!success && target.isFile()) {
        target.delete();
      }
    }
  }

  private static long effectiveMaxBytes(long globalMaxBytes, FileParameterOptions options) {
    if (options == null || !options.hasMaxSize()) {
      return globalMaxBytes;
    }
    return Math.min(globalMaxBytes, options.getMaxSizeBytes());
  }

  private static String detectContentType(File file, String fileName) throws IOException {
    String fromContent = null;
    BufferedInputStream input = new BufferedInputStream(new FileInputStream(file));
    try {
      fromContent = URLConnection.guessContentTypeFromStream(input);
    } finally {
      input.close();
    }
    if (fromContent != null && fromContent.length() > 0) {
      return fromContent;
    }
    String fromPath = Files.probeContentType(file.toPath());
    if (fromPath != null && fromPath.length() > 0) {
      return fromPath;
    }
    String fromName = URLConnection.guessContentTypeFromName(fileName);
    if (fromName != null && fromName.length() > 0) {
      return fromName;
    }
    return "application/octet-stream";
  }

  private static String sanitizeFileName(String value) {
    String name = value == null ? "upload.bin" : value.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    name = name.replaceAll("[^A-Za-z0-9._-]", "_");
    return name.length() == 0 ? "upload.bin" : name;
  }

  private static long longProperty(Properties properties, String key, long fallback) {
    try {
      return Long.parseLong(properties.getProperty(key, Long.toString(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int intProperty(Properties properties, String key, int fallback) {
    try {
      return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static boolean equals(String left, String right) {
    return left == null ? right == null : left.equals(right);
  }

  private static boolean buildContainsMarkerWhenPresent(FileUploadRecord record, String secret, String parameterName, Map<String, String> rawBuildParameters, Map<String, String> resolvedBuildParameters) {
    if (parameterName == null || parameterName.length() == 0) {
      return false;
    }
    String marker = FileUploadMarker.create(record.getToken(), secret, record.getFileName());
    boolean rawPresent = contains(rawBuildParameters, parameterName);
    boolean resolvedPresent = contains(resolvedBuildParameters, parameterName);
    boolean rawMatches = marker.equals(value(rawBuildParameters, parameterName));
    boolean resolvedMatches = marker.equals(value(resolvedBuildParameters, parameterName));
    FileParameterDiagnostics.debug(LOG, "marker check: tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(record.getToken()) + ", parameterName=" + parameterName + ", rawPresent=" + rawPresent + ", resolvedPresent=" + resolvedPresent + ", rawMatches=" + rawMatches + ", resolvedMatches=" + resolvedMatches + ", rawValue=" + FileParameterDiagnostics.markerSummary(value(rawBuildParameters, parameterName)) + ", resolvedValue=" + FileParameterDiagnostics.markerSummary(value(resolvedBuildParameters, parameterName)));
    return rawMatches || resolvedMatches;
  }

  private static boolean ownerMatches(FileUploadRecord record, long buildOwnerUserId) {
    if (buildOwnerUserId < 0) {
      LOG.warning("TeamCity did not expose a running build owner for File parameter download; continuing with marker/build/agent binding. tokenPrefix=" + tokenPrefix(record.getToken()) + ", uploadUserId=" + record.getUserId());
      return true;
    }
    return record.getUserId() >= 0 && buildOwnerUserId >= 0 && record.getUserId() == buildOwnerUserId;
  }

  private static String value(Map<String, String> values, String key) {
    return values == null ? null : values.get(key);
  }

  private static String tokenPrefix(String token) {
    if (token == null || token.length() <= 8) {
      return "";
    }
    return token.substring(0, 8);
  }

  private static boolean contains(Map<String, String> values, String key) {
    return values != null && values.containsKey(key);
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

  private static String hashSecret(String token, String secret) throws IOException {
    if (token == null || secret == null) {
      return "";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((token + ":" + secret).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IOException("SHA-256 is not available", e);
    }
  }
}
