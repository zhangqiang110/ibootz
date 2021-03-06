package top.bootz.security.core.authorize;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.ExpressionUrlAuthorizationConfigurer;
import org.springframework.stereotype.Component;

import top.bootz.security.core.SecurityConstants;
import top.bootz.security.core.properties.SecurityProperties;

/**
 * 核心模块的授权配置提供器，安全模块涉及的url的授权配置在这里。
 * 
 * @author Zhangq<momogoing@163.com>
 * @datetime 2018年10月27日 上午9:13:11
 */
@Component
@Order(Integer.MIN_VALUE)
public class CoreAuthorizeConfigProvider implements AuthorizeConfigProvider {

    @Autowired
    private SecurityProperties securityProperties;

    @Override
    public boolean config(ExpressionUrlAuthorizationConfigurer<HttpSecurity>.ExpressionInterceptUrlRegistry config) {
        // @formatter:off
        config
            .antMatchers(HttpMethod.OPTIONS, "/**").permitAll() // options请求不认证
            .antMatchers("/",
                SecurityConstants.DEFAULT_UNAUTHENTICATION_URL,
                SecurityConstants.DEFAULT_SIGN_IN_PROCESSING_URL_FORM,
                SecurityConstants.DEFAULT_SIGN_IN_PROCESSING_URL_MOBILE,
                SecurityConstants.DEFAULT_SIGN_IN_PROCESSING_URL_OPENID,
                SecurityConstants.DEFAULT_VERIFICATION_CODE_URL_PREFIX + "/*",
                securityProperties.getSession().getSignInUrl(), // 登录页面路径
                securityProperties.getSession().getSignUpUrl(), // 注册页面路径
                securityProperties.getSession().getSessionInvalidUrl() // session失效之后的处理路径
            ).permitAll();
        // @formatter:on

        if (StringUtils.isNotBlank(securityProperties.getSession().getSignOutUrl())) {
            config.antMatchers(securityProperties.getSession().getSignOutUrl()).permitAll();
        }

        return false;
    }

}
