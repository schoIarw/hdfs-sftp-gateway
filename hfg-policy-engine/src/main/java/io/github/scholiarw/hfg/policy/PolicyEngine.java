package io.github.scholiarw.hfg.policy;

import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import io.github.scholiarw.hfg.contract.UserSnapshot;

public final class PolicyEngine {
  private final PathResolver resolver;

  public PolicyEngine(PathResolver resolver) {
    this.resolver = resolver;
  }

  public PathResolver.ResolvedPath requireRead(UserSnapshot user, String cwd, String path) {
    var resolved = resolver.resolve(user.directories(), cwd, path);
    if (!resolved.grant().accessMode().canRead()) deny();
    return resolved;
  }

  public PathResolver.ResolvedPath requireWrite(UserSnapshot user, String cwd, String path) {
    var resolved = resolver.resolve(user.directories(), cwd, path);
    if (resolved.grant().accessMode() != AccessMode.READ_WRITE) deny();
    return resolved;
  }

  private static void deny() {
    throw new HfgException(HfgErrorCode.PERMISSION_DENIED, "Operation is not permitted");
  }
}
