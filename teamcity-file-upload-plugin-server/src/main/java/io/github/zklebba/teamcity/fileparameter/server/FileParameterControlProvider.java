package io.github.zklebba.teamcity.fileparameter.server;

import io.github.zklebba.teamcity.fileparameter.FileParameterConstants;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.controllers.BasePropertiesBean;
import jetbrains.buildServer.controllers.parameters.InvalidParametersException;
import jetbrains.buildServer.controllers.parameters.ParameterContext;
import jetbrains.buildServer.controllers.parameters.ParameterEditContext;
import jetbrains.buildServer.controllers.parameters.ParameterRenderContext;
import jetbrains.buildServer.controllers.parameters.api.ParameterControlProviderAdapter;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class FileParameterControlProvider extends ParameterControlProviderAdapter {
  private final PluginDescriptor descriptor;

  public FileParameterControlProvider(PluginDescriptor descriptor) {
    this.descriptor = descriptor;
  }

  @Override
  public String getParameterType() {
    return FileParameterConstants.PARAMETER_TYPE;
  }

  @Override
  public String getParameterDescription() {
    return "File upload";
  }

  @Override
  public ModelAndView renderControl(HttpServletRequest request, ParameterRenderContext context) throws InvalidParametersException {
    String value = context.getParameter() == null ? "" : context.getParameter().getValue();
    String renderValue = FileUploadMarker.parse(value) == null ? "" : value;
    ModelAndView view = new ModelAndView(descriptor.getPluginResourcesPath("fileParameterControl.jsp"));
    FileParameterOptions options = FileParameterOptions.from(context.getDescription());
    view.addObject("context", context);
    view.addObject("parameterValue", renderValue);
    view.addObject("parameterName", context.getParameter() == null ? "" : context.getParameter().getName());
    view.addObject("maxSizeBytes", Long.toString(options.getMaxSizeBytes()));
    view.addObject("allowedTypes", options.getAllowedTypes());
    return view;
  }

  @Override
  public ModelAndView renderSpecEditor(HttpServletRequest request, ParameterEditContext context) throws InvalidParametersException {
    ModelAndView view = new ModelAndView(descriptor.getPluginResourcesPath("fileParameterSpecEditor.jsp"));
    Map<String, String> arguments = context.getDescription() == null ? null : context.getDescription().getParameterTypeArguments();
    Map<String, String> properties = new LinkedHashMap<String, String>();
    if (arguments != null) {
      properties.putAll(arguments);
    }
    properties.put(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE, maxSizeValue(arguments));
    view.addObject("context", context);
    view.addObject("propertiesBean", new BasePropertiesBean(properties));
    return view;
  }

  @Override
  public Collection<InvalidProperty> validateSpecEditorParameters(Map<String, String> parameters) {
    Collection<InvalidProperty> result = new ArrayList<InvalidProperty>();
    String maxSize = value(parameters, FileParameterConstants.PARAMETER_OPTION_MAX_SIZE);
    if (maxSize.length() > 0 && FileParameterOptions.parseMaxSize(maxSize) <= 0) {
      result.add(new InvalidProperty(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE, "Maximum file size must be a positive integer with B, KB, or MB suffix, for example 512KB."));
    }
    String allowedTypes = value(parameters, FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES);
    String invalidType = FileParameterOptions.invalidAllowedType(allowedTypes);
    if (invalidType != null) {
      result.add(new InvalidProperty(FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES, "Invalid file type rule: " + invalidType));
    }
    return result;
  }

  @Override
  public Map<String, String> convertSpecEditorParameters(Map<String, String> parameters) throws InvalidParametersException {
    Map<String, String> result = new HashMap<String, String>();
    if (parameters != null) {
      result.putAll(parameters);
    }

    String maxSize = FileParameterOptions.normalizeMaxSize(value(parameters, FileParameterConstants.PARAMETER_OPTION_MAX_SIZE));
    result.remove(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE);
    result.remove(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE_MB);
    if (maxSize.length() > 0 && FileParameterOptions.parseMaxSize(maxSize) <= 0) {
      throw new InvalidParametersException("Maximum file size must be a positive integer with B, KB, or MB suffix, for example 512KB.");
    }
    if (maxSize.length() > 0) {
      result.put(FileParameterConstants.PARAMETER_OPTION_MAX_SIZE, maxSize);
    }
    String invalidType = FileParameterOptions.invalidAllowedType(value(parameters, FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES));
    if (invalidType != null) {
      throw new InvalidParametersException("Invalid file type rule: " + invalidType);
    }
    String allowedTypes = FileParameterOptions.normalizeAllowedTypes(value(parameters, FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES));
    result.remove(FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES);
    if (allowedTypes.length() > 0) {
      result.put(FileParameterConstants.PARAMETER_OPTION_ALLOWED_TYPES, allowedTypes);
    }
    return result;
  }

  @Override
  public void validateDefaultParameterValue(ParameterContext context, String value) throws InvalidParametersException {
    if (value != null && value.trim().length() > 0 && FileUploadMarker.parse(value) == null) {
      throw new InvalidParametersException("The default value must be empty or produced by the File parameter upload control.");
    }
  }

  @Override
  public Collection<InvalidProperty> validateParameterValue(HttpServletRequest request, ParameterRenderContext context, String value) throws InvalidParametersException {
    Collection<InvalidProperty> result = new ArrayList<InvalidProperty>();
    String submitted = request.getParameter(context.getId());
    if (submitted == null || submitted.trim().length() == 0) {
      result.add(new InvalidProperty(context.getId(), "Choose and upload a file for this File parameter."));
      return result;
    }
    if (FileUploadMarker.parse(submitted) == null) {
      result.add(new InvalidProperty(context.getId(), "The File parameter value must be produced by the upload control."));
    }
    return result;
  }

  @Override
  public String convertParameterValue(HttpServletRequest request, ParameterRenderContext context, String value) throws InvalidParametersException {
    String submitted = request.getParameter(context.getId());
    return submitted == null ? "" : submitted;
  }

  @Override
  public String presentParameterValue(ParameterContext context, String value) {
    FileUploadMarker marker = FileUploadMarker.parse(value);
    if (marker == null) {
      return value == null ? "" : value;
    }
    return "Uploaded file: " + marker.getFileName();
  }

  private static String value(Map<String, String> values, String key) {
    if (values == null) {
      return "";
    }
    String value = values.get(key);
    return value == null ? "" : value.trim();
  }

  private static String maxSizeValue(Map<String, String> arguments) {
    String maxSize = value(arguments, FileParameterConstants.PARAMETER_OPTION_MAX_SIZE);
    if (maxSize.length() > 0) {
      return maxSize;
    }
    String legacyMaxSizeMb = value(arguments, FileParameterConstants.PARAMETER_OPTION_MAX_SIZE_MB);
    return legacyMaxSizeMb.length() == 0 ? "" : legacyMaxSizeMb + "MB";
  }

}
