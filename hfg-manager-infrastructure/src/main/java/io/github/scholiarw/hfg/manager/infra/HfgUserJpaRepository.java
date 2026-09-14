package io.github.scholiarw.hfg.manager.infra;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface HfgUserJpaRepository extends JpaRepository<HfgUserEntity, UUID> {
  Optional<HfgUserEntity> findByUsername(String username);

  @Query(
      "select u from HfgUserEntity u where :q='' or lower(u.username) like lower(concat('%',:q,'%')) or lower(coalesce(u.department,'')) like lower(concat('%',:q,'%')) or lower(coalesce(u.businessDomain,'')) like lower(concat('%',:q,'%')) order by u.createdAt desc")
  List<HfgUserEntity> search(@Param("q") String query, Pageable pageable);

  @Query(
      "select count(u) from HfgUserEntity u where :q='' or lower(u.username) like lower(concat('%',:q,'%')) or lower(coalesce(u.department,'')) like lower(concat('%',:q,'%')) or lower(coalesce(u.businessDomain,'')) like lower(concat('%',:q,'%'))")
  long countSearch(@Param("q") String query);
}
