package io.github.zklebba.teamcity.fileparameter.agent;

import io.github.zklebba.teamcity.fileparameter.FileParameterDiagnostics;
import io.github.zklebba.teamcity.fileparameter.FileUploadMarker;
import jetbrains.buildServer.agent.parameters.ParameterResolverAgentProvider;
import jetbrains.buildServer.parameters.ContextVariables;
import jetbrains.buildServer.parameters.ParameterResolver;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.parameters.ProcessingResult;

import java.io.IOException;
import java.util.logging.Logger;

public class FileParameterResolverProvider implements ParameterResolverAgentProvider {
  private static final Logger LOG = Logger.getLogger(FileParameterResolverProvider.class.getName());
  private final FileParameterAgentService service;

  public FileParameterResolverProvider(FileParameterAgentService service) {
    this.service = service;
  }

  @Override
  public ParameterResolver getParameterResolver(ContextVariables contextVariables) {
    return new ParameterResolver() {
      @Override
      public ProcessingResult resolve(String value, String key, ParametersProvider parametersProvider) {
        FileUploadMarker marker = FileUploadMarker.parse(value);
        if (marker == null) {
          return result(value, false, true);
        }
        try {
          FileParameterDiagnostics.debug(LOG, "resolver downloading marker: key=" + key + ", tokenPrefix=" + FileParameterDiagnostics.tokenPrefix(marker.getToken()) + ", fileName=" + marker.getFileName());
          return result(service.resolve(marker, key), true, true);
        } catch (IOException e) {
          String message = "Cannot download TeamCity File parameter '" + key + "': " + e.getMessage();
          service.reportFailure(message, e);
          throw new IllegalStateException(message, e);
        }
      }
    };
  }

  private static ProcessingResult result(final String value, final boolean modified, final boolean fullyResolved) {
    return new ProcessingResult() {
      @Override
      public boolean isModified() {
        return modified;
      }

      @Override
      public String getResult() {
        return value;
      }

      @Override
      public boolean isFullyResolved() {
        return fullyResolved;
      }
    };
  }
}
