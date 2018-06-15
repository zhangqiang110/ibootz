package top.bootz.user.persist.mysql.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.repository.NoRepositoryBean;
import top.bootz.user.entity.mysql.auth.MapAuthorityElement;

/**
 * @author John
 * 2018年6月11日 下午9:52:50
 */

@NoRepositoryBean
public interface MapAuthorityElementDao extends JpaRepository<MapAuthorityElement, Long> {

}
