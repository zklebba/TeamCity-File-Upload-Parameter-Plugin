package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import jetbrains.buildServer.serverSide.ControlDescription;

import java.util.Locale;
import java.util.Map;

public final class FileParameterOptions {
  private final long maxSizeBytes;
  private final String allowedTypes;

  private FileParameterOptions(long maxSizeBytes, String allowedTypes) {
    this.maxSizeBytes = maxSizeBytes;
    this.allowedTypes = allowedTypes == null ? "" : allowedTypes.trim();
  }

  public static FileParameterOptions from(ControlDescription description) {
    Map<String, String> arguments = description == null ? null : description.getParameterTypeArguments();
    if (arguments == null) {
      return new FileParameterOptions(0L, "");
    }
    return new FileParameterOptions(parseMaxSize(arguments), normalizeAllowedTypes(arguments.get(FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES)));
  }

  public static long parseMaxSize(Map<String, String> arguments) {
    if (arguments == null) {
      return 0L;
    }
    long maxSize = parseMaxSize(arguments.get(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE));
    if (maxSize != 0L) {
      return maxSize;
    }
    return parseLegacyMaxSizeMb(arguments.get(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE_MB));
  }

  public static long parseMaxSize(String value) {
    if (value == null || value.trim().length() == 0) {
      return 0L;
    }
    String normalized = normalizeMaxSize(value);
    long multiplier;
    String number;
    if (normalized.endsWith("KB")) {
      multiplier = 1024L;
      number = normalized.substring(0, normalized.length() - 2);
    } else if (normalized.endsWith("MB")) {
      multiplier = 1024L * 1024L;
      number = normalized.substring(0, normalized.length() - 2);
    } else if (normalized.endsWith("B")) {
      multiplier = 1L;
      number = normalized.substring(0, normalized.length() - 1);
    } else {
      return -1L;
    }
    try {
      long amount = Long.parseLong(number);
      if (amount <= 0) {
        return -1L;
      }
      if (amount > Long.MAX_VALUE / multiplier) {
        return -1L;
      }
      return amount * multiplier;
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  public static long parseLegacyMaxSizeMb(String value) {
    if (value == null || value.trim().length() == 0) {
      return 0L;
    }
    try {
      long megabytes = Long.parseLong(value.trim());
      if (megabytes <= 0 || megabytes > Long.MAX_VALUE / 1024L / 1024L) {
        return -1L;
      }
      return megabytes * 1024L * 1024L;
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  public static String normalizeMaxSize(String value) {
    if (value == null || value.trim().length() == 0) {
      return "";
    }
    return value.trim().toUpperCase(Locale.ROOT).replace(" ", "");
  }

  public static String normalizeAllowedTypes(String value) {
    if (value == null || value.trim().length() == 0) {
      return "";
    }
    StringBuilder result = new StringBuilder();
    for (String token : value.split(",")) {
      String normalized = token.trim().toLowerCase(Locale.ROOT);
      if (normalized.length() == 0) {
        continue;
      }
      if (result.length() > 0) {
        result.append(',');
      }
      result.append(normalized);
    }
    return result.toString();
  }

  public static String invalidAllowedType(String value) {
    if (value == null || value.trim().length() == 0) {
      return null;
    }
    for (String token : value.split(",")) {
      String rule = token.trim().toLowerCase(Locale.ROOT);
      if (rule.length() == 0) {
        continue;
      }
      if (rule.charAt(0) == '.') {
        if (rule.length() > 1 && rule.matches("\\.[a-z0-9][a-z0-9._+-]*")) {
          continue;
        }
        return token.trim();
      }
      if (rule.matches("[a-z0-9][a-z0-9.+-]*/(\\*|[a-z0-9][a-z0-9.+-]*)")) {
        continue;
      }
      return token.trim();
    }
    return null;
  }

  public boolean hasMaxSize() {
    return maxSizeBytes > 0;
  }

  public long getMaxSizeBytes() {
    return maxSizeBytes;
  }

  public String getAllowedTypes() {
    return allowedTypes;
  }

  public boolean hasAllowedTypes() {
    return allowedTypes.length() > 0;
  }

  public boolean isAllowed(String fileName, String detectedContentType) {
    if (!hasAllowedTypes()) {
      return true;
    }
    String extension = extension(fileName);
    String detected = normalizeMime(detectedContentType);
    for (String token : allowedTypes.split(",")) {
      String rule = token.trim().toLowerCase(Locale.ROOT);
      if (rule.length() == 0) {
        continue;
      }
      if (rule.charAt(0) == '.') {
        if (rule.equals(extension)) {
          return true;
        }
        continue;
      }
      if (rule.endsWith("/*")) {
        String prefix = rule.substring(0, rule.length() - 1);
        if (detected != null && detected.startsWith(prefix)) {
          return true;
        }
        continue;
      }
      if (rule.equals(detected)) {
        return true;
      }
    }
    return false;
  }

  private static String extension(String fileName) {
    if (fileName == null) {
      return "";
    }
    int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    int dot = fileName.lastIndexOf('.');
    if (dot <= slash || dot == fileName.length() - 1) {
      return "";
    }
    return fileName.substring(dot).toLowerCase(Locale.ROOT);
  }

  private static String normalizeMime(String value) {
    if (value == null) {
      return null;
    }
    int semicolon = value.indexOf(';');
    String normalized = (semicolon >= 0 ? value.substring(0, semicolon) : value).trim().toLowerCase(Locale.ROOT);
    return normalized.length() == 0 ? null : normalized;
  }
}
