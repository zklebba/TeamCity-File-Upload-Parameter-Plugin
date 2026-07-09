package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.serverSide.ControlDescription;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.ReadOnlyAwareParameterType;

public class FileParameterType extends ReadOnlyAwareParameterType<String> {
  @Override
  public String getParameterType() {
    return FileParameterConstants.PARAMETER_TYPE;
  }

  @Override
  public String getValue(Parameter parameter) {
    return parameter == null ? "" : parameter.getValue();
  }

  @Override
  public String getValue(String value, ControlDescription controlDescription) {
    return value == null ? "" : value;
  }

  @Override
  public boolean isValid(String value, ControlDescription controlDescription) {
    return validate(value, controlDescription) == null;
  }

  @Override
  public boolean isValid(Parameter parameter) {
    return parameter != null && isValid(parameter.getValue(), parameter.getControlDescription());
  }

  @Override
  public boolean isValid(ControlDescription controlDescription) {
    return true;
  }

  @Override
  public String describe(ControlDescription controlDescription) {
    return "File upload";
  }

  @Override
  public String validate(String value, ControlDescription controlDescription) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    if (FileUploadMarker.parse(value) == null) {
      return "The value must be produced by the File parameter upload control.";
    }
    return null;
  }

  @Override
  public String validate(ControlDescription controlDescription) {
    if (controlDescription == null || controlDescription.getParameterTypeArguments() == null) {
      return null;
    }
    String maxSize = controlDescription.getParameterTypeArguments().get(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE);
    if (maxSize != null && maxSize.trim().length() > 0 && FileParameterOptions.parseMaxSize(maxSize) <= 0) {
      return "Maximum file size must be a positive integer with B, KB, or MB suffix, for example 512KB.";
    }
    String legacyMaxSizeMb = controlDescription.getParameterTypeArguments().get(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE_MB);
    if ((maxSize == null || maxSize.trim().length() == 0) && legacyMaxSizeMb != null && legacyMaxSizeMb.trim().length() > 0 && FileParameterOptions.parseLegacyMaxSizeMb(legacyMaxSizeMb) <= 0) {
      return "Maximum file size must be a positive integer number of MB.";
    }
    String allowedTypes = controlDescription.getParameterTypeArguments().get(FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES);
    String invalidType = FileParameterOptions.invalidAllowedType(allowedTypes);
    if (invalidType != null) {
      return "Invalid file type rule: " + invalidType;
    }
    return null;
  }

  @Override
  public String toString(String value) {
    return value == null ? "" : value;
  }

  @Override
  public boolean isSecureParameter(ControlDescription controlDescription) {
    return false;
  }
}
