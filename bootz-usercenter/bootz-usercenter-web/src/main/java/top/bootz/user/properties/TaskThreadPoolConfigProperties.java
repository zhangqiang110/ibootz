package top.bootz.user.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Desc: 读取自定义ExecutorTask配置文件
 * 
 * @author John
 * @dateTime: 2018年6月10日 下午10:08:51
 */

@Data
@NoArgsConstructor
@ConfigurationProperties(prefix = "spring.task.pool.custom")
public class TaskThreadPoolConfigProperties {

	private String threadNamePrefix;

	private int corePoolSize;

	private int maxPoolSize;

	private int keepAliveSeconds;

	private int queueCapacity;

}