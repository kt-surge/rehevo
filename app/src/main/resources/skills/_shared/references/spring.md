# Spring 面试重点

## IoC
- 创建与依赖解耦；@Component/@Bean 与第三方库；@Autowired 类型优先/@Resource 名称优先及冲突解析。
- 构造器/Setter/字段注入与测试/不可变性/循环依赖；singleton/prototype/request/session；生命周期：创建→属性→Aware→初始化→销毁；线程安全。

## AOP 与 MVC
- 切面/切点/通知/连接点；AOP 动态代理、AspectJ 织入；自调用默认不经过代理。
- MVC：DispatcherServlet、HandlerMapping→HandlerAdapter→ViewResolver；@ControllerAdvice/@ExceptionHandler；拦截器/过滤器。

## 事务（Spring Framework 6+）
- @Transactional：传播/隔离/rollbackFor；七种传播如 REQUIRED/REQUIRES_NEW/NESTED。
- 类代理默认支持 protected/package-visible，接口代理须公开接口方法；外部经代理且未设 publicMethodsOnly。private/final 限制看代理方式。
- 自调用无新的事务拦截，但可参与外层事务；初始化代理未就绪、吞异常、跨线程均须另析。
- 默认 RuntimeException/Error 回滚，checked Exception 不回滚；rollbackFor 或全局规则可改变结果。

## 循环依赖与 Boot
- setter 三级缓存：singletonObjects/earlySingletonObjects/singletonFactories；构造器循环依赖可 @Lazy；Boot 2.6+ 默认禁止循环依赖。
- 自动配置/Starter：@SpringBootApplication/@EnableAutoConfiguration、spring.factories/Imports、@ConditionalOnClass/@ConditionalOnMissingBean。
- 默认命令行 > 环境变量 > 配置数据；同位置 properties 优于 YAML 为独立规则；自定义 Environment/测试属性另查。

追问：项目 AOP。
