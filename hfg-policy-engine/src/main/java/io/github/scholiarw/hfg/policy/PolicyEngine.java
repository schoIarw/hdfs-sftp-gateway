package io.github.scholiarw.hfg.policy;

import io.github.scholiarw.hfg.contract.AccessMode;
import io.github.scholiarw.hfg.contract.HfgErrorCode;
import io.github.scholiarw.hfg.contract.HfgException;
import io.github.scholiarw.hfg.contract.UserSnapshot;
import java.util.function.Predicate;

public final class PolicyEngine {
  private final PathResolver resolver;

  public PolicyEngine(PathResolver resolver) {
    this.resolver = resolver;
  }

  public PathResolver.ResolvedPath requireRead(UserSnapshot user, String cwd, String path) {
    return requireRead(user, cwd, path, candidate -> false);
  }

  /** 解析并校验读权限；{@code storageExists} 用于判定同名真实目录是否遮蔽了虚拟挂载点。 */
  public PathResolver.ResolvedPath requireRead(
      UserSnapshot user, String cwd, String path, Predicate<String> storageExists) {
    var resolved = resolver.resolve(user.directories(), cwd, path, storageExists);
    if (!resolved.grant().accessMode().canRead()) deny();
    return resolved;
  }

  public PathResolver.ResolvedPath requireWrite(UserSnapshot user, String cwd, String path) {
    return requireWrite(user, cwd, path, candidate -> false);
  }

  public PathResolver.ResolvedPath requireWrite(
      UserSnapshot user, String cwd, String path, Predicate<String> storageExists) {
    var resolved = resolver.resolve(user.directories(), cwd, path, storageExists);
    if (resolved.grant().accessMode() != AccessMode.READ_WRITE) deny();
    return resolved;
  }

  private static void deny() {
    throw new HfgException(HfgErrorCode.PERMISSION_DENIED, "Operation is not permitted");
  }
}
