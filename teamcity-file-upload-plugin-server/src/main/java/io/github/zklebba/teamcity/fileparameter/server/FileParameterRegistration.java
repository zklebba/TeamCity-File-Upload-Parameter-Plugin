package io.github.zklebba.teamcity.fileparameter.server;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class FileParameterRegistration {
  private static final Set<String> IDS = Collections.synchronizedSet(new HashSet<String>());

  private FileParameterRegistration() {
  }

  static boolean claim(String id) {
    return IDS.add(id);
  }
}
