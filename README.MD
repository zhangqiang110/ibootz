项目通用模块 [bootz-common]     ：提供与业务无关，但是与全局应用相关的功能，例如全局工具类、全局常量，全局枚举字典、全局异常、工厂类、过滤器、监听器、生成器等
项目核心模块 [bootz-core]       ：提供一些业务相关基础类，另外一些依赖于spring等框架的功能也可以放置于该处。例如基础自定义注解，基础分页类，基础工具包
用户中心模块 [bootz-usercenter] ：实现用户的注册功能， 配合安全中心模块实现登录，授权，访问控制等功能
日志中心模块 [bootz-logcenter]  ：追踪查看各分项目日志记录，包括用户操作日志和应用日志
安全中心模块 [bootz-security]   : 负责1. 登录鉴权，包括用户鉴权和第三方访问应用鉴权； 2. 访问限流


小目标：
	集成elasticsearch
	集成Spring task
	集成Spring batch
	集成spring-security-oauth2
	集成spring-security
	测试Spring-data在webapi层面的使用：@EnableSpringDataWebSupport
	测试文档生成框架：Spring REST docs
	测试QueryDSL查询
	集成mybatis
	集成Quartz Scheduler
	
中期目标
	将整个Spring boot项目转换为Spring Cloud项目
	添加Docker支持
	
远期目标
	添加Kubernate支持
	