package io.github.zklebba.teamcity.fileparameter.server;

import java.io.File;

public class FileUploadRecord {
  private final String token;
  private final String secret;
  private final String secretHash;
  private final String fileName;
  private final File file;
  private final long size;
  private final long expiresAtMillis;
  private final long userId;
  private final String buildTypeId;
  private final long claimedBuildId;
  private final int claimedAgentId;
  private final boolean consumed;
  private final boolean downloading;

  public FileUploadRecord(String token, String secret, String secretHash, String fileName, File file, long size, long expiresAtMillis, long userId, String buildTypeId, long claimedBuildId, int claimedAgentId, boolean consumed, boolean downloading) {
    this.token = token;
    this.secret = secret;
    this.secretHash = secretHash;
    this.fileName = fileName;
    this.file = file;
    this.size = size;
    this.expiresAtMillis = expiresAtMillis;
    this.userId = userId;
    this.buildTypeId = buildTypeId;
    this.claimedBuildId = claimedBuildId;
    this.claimedAgentId = claimedAgentId;
    this.consumed = consumed;
    this.downloading = downloading;
  }

  public String getToken() {
    return token;
  }

  public String getSecret() {
    return secret;
  }

  public String getSecretHash() {
    return secretHash;
  }

  public String getFileName() {
    return fileName;
  }

  public File getFile() {
    return file;
  }

  public long getSize() {
    return size;
  }

  public long getExpiresAtMillis() {
    return expiresAtMillis;
  }

  public long getUserId() {
    return userId;
  }

  public String getBuildTypeId() {
    return buildTypeId;
  }

  public long getClaimedBuildId() {
    return claimedBuildId;
  }

  public int getClaimedAgentId() {
    return claimedAgentId;
  }

  public boolean isConsumed() {
    return consumed;
  }

  public boolean isDownloading() {
    return downloading;
  }

  public FileUploadRecord downloading(boolean value) {
    return new FileUploadRecord(token, secret, secretHash, fileName, file, size, expiresAtMillis, userId, buildTypeId, claimedBuildId, claimedAgentId, consumed, value);
  }

  public FileUploadRecord consumed(boolean value) {
    return new FileUploadRecord(token, secret, secretHash, fileName, file, size, expiresAtMillis, userId, buildTypeId, claimedBuildId, claimedAgentId, value, downloading);
  }

  public FileUploadRecord claimedBy(long buildId, int agentId) {
    return new FileUploadRecord(token, secret, secretHash, fileName, file, size, expiresAtMillis, userId, buildTypeId, buildId, agentId, consumed, downloading);
  }
}
