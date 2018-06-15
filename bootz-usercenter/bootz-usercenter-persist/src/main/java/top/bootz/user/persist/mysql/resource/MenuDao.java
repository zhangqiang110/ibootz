package top.bootz.user.persist.mysql.resource;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.repository.NoRepositoryBean;
import top.bootz.user.entity.mysql.resource.Menu;

/**
 * @author John
 * 2018年6月11日 下午10:01:02
 */

@NoRepositoryBean
public interface MenuDao extends JpaRepository<Menu, Long> {

}
