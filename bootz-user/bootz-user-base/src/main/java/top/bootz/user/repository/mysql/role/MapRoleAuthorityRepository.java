package top.bootz.user.repository.mysql.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import top.bootz.user.entity.mysql.role.MapRoleAuthority;

/**
 * @author John 2018年6月11日 下午9:52:50
 */

public interface MapRoleAuthorityRepository extends JpaRepository<MapRoleAuthority, Long>,
        JpaSpecificationExecutor<MapRoleAuthority>, QuerydslPredicateExecutor<MapRoleAuthority> {

}
