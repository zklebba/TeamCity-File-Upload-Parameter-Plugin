package io.github.zklebba.teamcity.fileparameter;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

public final class FileUploadMarker {
  private final String token;
  private final String secret;
  private final String fileName;

  private FileUploadMarker(String token, String secret, String fileName) {
    this.token = token;
    this.secret = secret;
    this.fileName = fileName;
  }

  public static String create(String token, String secret, String fileName) {
    return FileParameterConstants.MARKER_PREFIX + encode(token) + "/" + encode(secret) + "/" + encode(fileName);
  }

  public static FileUploadMarker parse(String value) {
    if (value == null || !value.startsWith(FileParameterConstants.MARKER_PREFIX)) {
      return null;
    }

    String rest = value.substring(FileParameterConstants.MARKER_PREFIX.length());
    int separator = rest.indexOf('/');
    int secondSeparator = separator < 0 ? -1 : rest.indexOf('/', separator + 1);
    if (separator <= 0 || secondSeparator <= separator + 1 || secondSeparator == rest.length() - 1) {
      return null;
    }

    return new FileUploadMarker(
        decode(rest.substring(0, separator)),
        decode(rest.substring(separator + 1, secondSeparator)),
        decode(rest.substring(secondSeparator + 1))
    );
  }

  public String getToken() {
    return token;
  }

  public String getSecret() {
    return secret;
  }

  public String getFileName() {
    return fileName;
  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String decode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }
}
