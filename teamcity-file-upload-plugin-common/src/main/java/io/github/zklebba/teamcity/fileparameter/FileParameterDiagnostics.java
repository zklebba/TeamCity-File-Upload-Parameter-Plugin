package io.github.zklebba.teamcity.fileparameter;

import java.util.Map;
import java.util.logging.Logger;

public final class FileParameterDiagnostics {
  private FileParameterDiagnostics() {
  }

  public static boolean enabled() {
    return Boolean.getBoolean(FileParameterConstants.PROPERTY_DEBUG);
  }

  public static void debug(Logger logger, String message) {
    if (enabled()) {
      logger.info("[TeamCity File Parameter debug] " + message);
    }
  }

  public static String tokenPrefix(String token) {
    if (token == null || token.length() == 0) {
      return "<missing>";
    }
    return token.length() <= 8 ? token : token.substring(0, 8);
  }

  public static String markerSummary(String value) {
    FileUploadMarker marker = FileUploadMarker.parse(value);
    if (marker == null) {
      return valueKind(value);
    }
    return "marker(tokenPrefix=" + tokenPrefix(marker.getToken()) + ", fileName=" + marker.getFileName() + ")";
  }

  public static String valueKind(String value) {
    if (value == null) {
      return "<missing>";
    }
    if (value.length() == 0) {
      return "<empty>";
    }
    return "<plain length=" + value.length() + ">";
  }

  public static String parameterMapSummary(Map<String, String> values, String parameterName) {
    if (values == null) {
      return "map=null";
    }
    if (!values.containsKey(parameterName)) {
      return "mapSize=" + values.size() + ", contains=false";
    }
    return "mapSize=" + values.size() + ", contains=true, value=" + markerSummary(values.get(parameterName));
  }

}
