# 注解驱动 MVC / IoC 框架使用指南

`io.github.wycst.wastnet.http.annotation` 是一套**注解驱动的 MVC + IoC 容器**框架，它构建在手工路由 `HttpRouterHandler` 之上（见 [http-router-guide.md](http-router-guide.md)），在启动期扫描指定包下的类，自动把 `@Controller` / `@Component` / `@Configuration` 等注册成路由与托管 Bean，无需手写 `route(...)`。

> **模块坐标**：该 MVC 层已独立为 Maven 模块 **`wastnet-mvc`**（`io.github.wycst:wastnet-mvc`），使用注解式 MVC 需在 `pom.xml` 中引入 `wastnet-mvc` 依赖（`wastnet-core` 仅含底层网络框架）。

本文档覆盖该模块的全部注解、运行时类与扩展点。拦截器的**深度细节**（三种机制对比、短路语义、异常兜底）已在 [http-interceptor-guide.md](http-interceptor-guide.md) 第 12 节讲解，本文仅补充**注解注册**部分并交叉引用。

---

## 目录

- [1. 概述](#1-概述)
  - [1.1 与 HttpRouterHandler 的关系](#11-与-httprouterhandler-的关系)
  - [1.2 与 Spring MVC 对照](#12-与-spring-mvc-对照)
  - [1.3 最小启动](#13-最小启动)
- [2. 控制器与端点](#2-控制器与端点)
  - [2.1 @Controller / @RestController](#21-controller--restcontroller)
  - [2.2 @Endpoint](#22-endpoint)
  - [2.3 路径与路径变量](#23-路径与路径变量)
  - [2.4 允许的方法 allowMethods](#24-允许的方法-allowmethods)
  - [2.5 响应类型与 @ResponseBody](#25-响应类型与-responsebody)
- [3. 方法参数绑定](#3-方法参数绑定)
  - [3.1 特殊类型参数](#31-特殊类型参数)
  - [3.2 @PathParam](#32-pathparam)
  - [3.3 @RequestParam](#33-requestparam)
  - [3.4 @RequestBody](#34-requestbody)
  - [3.5 内置类型转换器](#35-内置类型转换器)
  - [3.6 绑定失败的默认行为](#36-绑定失败的默认行为)
  - [3.7 @RequestHeader](#37-requestheader)
- [4. 消息转换](#4-消息转换)
- [5. IoC 容器](#5-ioc-容器)
  - [5.1 @Component](#51-component)
  - [5.2 @Configuration + @Bean](#52-configuration--bean)
  - [5.3 构造器注入与 @Inject](#53-构造器注入与-inject)
  - [5.4 @Value 与配置来源](#54-value-与配置来源)
  - [5.5 生命周期 @PostConstruct / @PreDestroy](#55-生命周期-postconstruct--predestroy)
  - [5.6 扫描与依赖解析流程](#56-扫描与依赖解析流程)
- [6. 拦截器（注解注册）](#6-拦截器注解注册)
- [7. SSE（@Sse）](#7-sse-sse)
- [8. WebSocket（@WebSocket）](#8-websocket-websocket)
- [9. 开发热重载](#9-开发热重载)
- [10. 可扩展点](#10-可扩展点)
  - [10.1 AnnotationResolver 与 *By 桥接](#101-annotationresolver-与-by-桥接)
  - [10.2 ComponentEnhancer](#102-componentenhancer)
  - [10.3 BeanRegistrationHandler + @Registration](#103-beanregistrationhandler--registration)
- [11. API 速查表](#11-api-速查表)
- [12. 常见问题与陷阱](#12-常见问题与陷阱)

---

## 1. 概述

`AnnotationRouterHandler` 是入口类，它**继承** `HttpRouterHandler`。所有手工路由能力（精确/前缀/正则匹配、`ws(...)`、`proxy(...)`、`resource(...)` 等）在注解路由中依然可用，二者可混用：扫描注册的路由标记为 `AnnotationRoute`，热重载时只清掉扫描产物、保留手工注册的路由。

### 1.1 与 HttpRouterHandler 的关系

```
HttpRouterHandler              （手工路由：route / exactRoute / ws / proxy / resource）
   └─ AnnotationRouterHandler   （包扫描：@Controller / @Component / @Configuration / @WebSocket）
        ├─ scanPackages(...)    准备期前设置扫描包
        ├─ BeanContainer        内置轻量 IoC 容器（扫描期构建）
        └─ DevHotReloader       开发热重载引擎
```

### 1.2 与 Spring MVC 对照

| wastnet 注解 | Spring 等价 | 说明 |
|:-------------|:------------|:-----|
| `@Controller` | `@Controller` | 类级；`value()` 为 base path（可空） |
| `@RestController` | `@RestController` | 等价于 `@Controller` + 类级 `@ResponseBody` |
| `@Endpoint` | `@RequestMapping` / `@GetMapping`… | 方法级路由；`allowMethods` 限定方法 |
| `@Component` | `@Component` | 托管组件，可注入 |
| `@Configuration` + `@Bean` | `@Configuration` + `@Bean` | 工厂方法 Bean |
| `@Inject` | `@Autowired` | 字段 / 构造器 / 参数注入 |
| `@Value` | `@Value` | `${key:default}` 占位符 |
| `@PathParam` | `@PathVariable` | 路径变量 |
| `@RequestParam` | `@RequestParam` | 查询 / 表单参数 |
| `@RequestBody` | `@RequestBody` | 反序列化请求体 |
| `@ResponseBody` | `@ResponseBody` | 序列化返回值 |
| `@RequestHeader` | `@RequestHeader` | 请求头绑定（内置，与 `@RequestParam` 同机制） |
| `@PostConstruct` / `@PreDestroy` | `javax.annotation.*` | 生命周期回调（框架自带，无需额外依赖） |
| `@Interceptor` + `@WithInterceptor` | `HandlerInterceptor` / 方法安全 | 路由级拦截（见第 6 节） |

### 1.3 最小启动

```java
import io.github.wycst.wastnet.http.HTTPServer;
import io.github.wycst.wastnet.http.annotation.AnnotationRouterHandler;

public class App {
    public static void main(String[] args) throws Exception {
        AnnotationRouterHandler router = new AnnotationRouterHandler();
        router.scanPackages("com.example.controller");   // 必须在 start() 之前
        HTTPServer.of(8080).requestHandler(router).start();
    }
}
```

> **时机约束**：`scanPackages()`、`.configFiles(...)`、`.ignoreInternalConfig(...)`、`.hotReloadWatchExclude(...)` 都必须在 `server.start()` 之前调用；`start()` 会触发 `prepare()` 冻结扫描包列表，之后再调用会抛 `IllegalStateException`。

> 未调用 `scanPackages(...)` 时，框架 **回退到主类（main 方法所在类）所在的包** 作为扫描根。

---

## 2. 控制器与端点

### 2.1 @Controller / @RestController

| 注解 | `@Target` | 属性 | 说明 |
|:-----|:----------|:-----|:-----|
| `@Controller` | `TYPE`（`@Inherited`） | `value()` 默认 `""` | base path 前缀；子类因 `@Inherited` 也被识别为控制器 |
| `@RestController` | `TYPE`（`@Inherited`） | `value()` 默认 `""` | 同上，并等价于给**每个** `@Endpoint` 方法加 `@ResponseBody` |

`@RestController` 仅影响响应序列化开关，路由行为与 `@Controller` 完全一致。

### 2.2 @Endpoint

```java
@Target(ElementType.METHOD)
public @interface Endpoint {
    String value();                 // 端点路径（相对 base path），必填、无默认值
    HttpMethod[] allowMethods() default {};   // 空 = 允许任意方法
    ContentType responseType() default ContentType.JSON;
}
```

- `value()` **必填**（无默认值）：不能写裸 `@Endpoint`，必须带值；**但前导 `/` 写不写都行**，缺失时 `buildFullPath` 自动补（见下例）。

### 2.3 路径与路径变量

路径变量支持 **两种语法**，均可带内联正则：

- wastnet 风格：`${id}`、`${id:[0-9]+}`
- Spring 风格：`{id}`、`{id:[0-9]+}`

```java
@Controller("/user")
public class UserCtrl {
    @Endpoint("/${id}")                 // ✓ 访问 /user/123    （前导 / 可省略，等价写法 @Endpoint("${id}")）
    @ResponseBody
    public Object byId(@PathParam("id") long id) { ... }

    @Endpoint("/{name:[a-z]+}")         // ✓ 访问 /user/tom
    @ResponseBody
    public Object byName(@PathParam("name") String name) { ... }

    @Endpoint("profile")                // ✓ 访问 /user/profile（没有 / 也行，buildFullPath 自动补）
    public void profile() { ... }

    @Endpoint("")                       // ✗ 空串/纯空白：扫描期 if(!path.isEmpty()) 为假，该方法被跳过，不注册路由
    public void root() { ... }
}
```

- 路径中出现 `{` 即视为正则路由，框架在扫描期预编译 `^...$` 模式；否则走精确匹配。
- `@PathParam("x")` 的名字**必须在路径中存在对应变量**，否则注册时抛 `IllegalArgumentException`（fail-fast）。

### 2.4 允许的方法 allowMethods

```java
@Endpoint(value = "/upload", allowMethods = HttpMethod.POST)
```

- `allowMethods` 为空 → 匹配任意 HTTP 方法。
- 非空 → **仅白名单**（allow-list，非 deny-list）；未列出的方法被拒绝。

### 2.5 响应类型与 @ResponseBody

`@ResponseBody` 可在**类级**（`@RestController` 隐含）或**方法级**声明。返回值序列化的判定：

- 方法返回类型非 `void`，且（类有 `@ResponseBody` 注解 **或** 方法有 `@ResponseBody` 注解），且配置了 `HttpMessageConverter` → 自动序列化。
- 返回 `void` → 视为自行写响应（直接操作 `HttpResponse`）。

`ContentType` 枚举决定 `Content-Type` 响应头（`@Endpoint.responseType()` 默认 `JSON`）：

| 值 | 响应头 |
|:----|:-------|
| `JSON` | `application/json;charset=utf-8` |
| `TEXT` | `text/plain;charset=utf-8` |
| `HTML` | `text/html;charset=utf-8` |
| `XML` | `application/xml;charset=utf-8` |
| `CUSTOM` | `null`，由 `HttpMessageConverter` 自己决定 Content-Type |

> **`TEXT` 类型开箱即用**：`AnnotationRouterHandler` 构造时已内置零依赖的 `TEXT` 转换器（`text/plain;charset=utf-8`，`String.valueOf(value)` 输出），声明 `responseType = ContentType.TEXT` 的 `@ResponseBody` 端点无需再配置；如需自定义可用 `.messageConverter(ContentType.TEXT, customConverter)` 覆盖。

---

## 3. 方法参数绑定

### 3.1 特殊类型参数

下列类型**无需注解**，按位置直接注入：

| 参数类型 | 含义 |
|:---------|:-----|
| `HttpRequest` | 当前请求 |
| `HttpResponse` | 当前响应（配合 `void` 返回自行写出） |
| `SseEmitter` | **仅** `@Sse` 端点；**按参数类型识别、无需任何注解**；普通 `@Endpoint` 声明会注册失败 |

> `HttpRequest` / `HttpResponse` / `SseEmitter` 三个内置类型**按类型识别、无需任何注解**；其余任何参数都必须带注解，否则运行期该参数绑定为 `null`（基本类型则绑定为对应零值）。推荐始终用 `@PathParam` / `@RequestParam` / `@RequestBody` 显式声明。

### 3.2 @PathParam

```java
@Target(ElementType.PARAMETER)
public @interface PathParam {
    String value();   // 变量名，须与路径中的 ${name}/{name} 对应
}
```

- 取值自对应路径段，经类型转换器转成目标类型（`long` / `Long` / `String` / `enum` / `BigDecimal` / `LocalDate` 等）。
- 名字与路径变量不匹配 → 注册期 `IllegalArgumentException`。

### 3.3 @RequestParam

```java
@Target(ElementType.PARAMETER)
public @interface RequestParam {
    String value();                   // 参数名
    boolean required() default true;  // 是否必填
    String defaultValue() default ""; // 缺省值（required=false 时生效）
}
```

支持三类绑定：

| 目标类型 | 行为 |
|:---------|:-----|
| 标量（`String` / 基本类型 / `BigDecimal` …） | 取 `request.getParameter(name)`；缺省走 `defaultValue`；必填缺失抛异常 → 400 |
| 数组 / `Collection`（`String[]`、`List<Integer>` 等） | 取 `getParameterValues(name)`；元素经转换器转换；`List<String>` 保留原始列表 |
| `MultipartField` / `MultipartField[]` | 文件上传；未上传时单文件为 `null`、数组为 `null` |

```java
@Endpoint("/search")
@ResponseBody
public Object search(@RequestParam("q") String q,
                     @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                     @RequestParam("tag") String[] tags) { ... }

@Endpoint(value = "/upload", allowMethods = HttpMethod.POST)
@ResponseBody
public Object upload(@RequestParam("file") MultipartField file) { ... }
```

> 集合元素类型：`List<Integer>` 通过泛型实参推导元素类型；**裸** `List`（无泛型）回退到 `String`。

### 3.4 @RequestBody

```java
@Target(ElementType.PARAMETER)
public @interface RequestBody {}   // 无属性
```

- 框架调用 `HttpMessageConverter.read(request, converterConfig, parameterizedType)` 反序列化。
- `parameterizedType` 保留完整泛型（`List<User>` 不会丢类型）。
- **仅允许在 `@Endpoint` 上**；在 `@Sse` 上声明会注册失败（`@Sse` 产生流、不消费 body）。

### 3.5 内置类型转换器

`@PathParam` / `@RequestParam` / `@Value` 共用同一套转换器注册表（`ParamValueConverters`）：

| 类型 | 是否可覆盖 |
|:-----|:----------|
| `String`、8 种基本类型及其包装类 | **锁定**，不可覆盖 |
| `BigDecimal`、`BigInteger`、`LocalDate`、`LocalDateTime` | 可覆盖 |
| 任意 `enum` | 通用支持（`Enum.valueOf`） |
| 其他自定义类型 | 须注册，否则注册/绑定期抛异常 |

注册自定义转换器（泛型、类型安全）：

```java
router.registerParamConverter(MyEnum.class, MyEnum::fromString);
```

> 内置锁定类型调用 `registerParamConverter` 会抛 `IllegalArgumentException`。

### 3.6 绑定失败的默认行为

参数解析 / 路径绑定 / body 解析**失败**时，框架捕获异常并直接返回 **`400 Bad Request`**（`text/plain`），不会进入端点方法。属于客户端错误，无需在端点内处理。

### 3.7 @RequestHeader

```java
@Target(ElementType.PARAMETER)
public @interface RequestHeader {
    String value();                       // 头名（大小写不敏感）
    boolean required() default true;     // 是否必填
    String defaultValue() default "";    // 缺省值（required=false 时生效）
}
```

- 框架内置注解，与 `@RequestParam` 同机制：扫描期识别 `headerParamAnnotations`、运行期经 `AnnotationRouteUtils.resolveRequestHeader` 取值。
- 支持的目标类型：

| 目标类型 | 行为 |
|:---------|:-----|
| `String` | 取 `request.getHeader(name)`（大小写不敏感） |
| 任意已注册 / 内置类型（`int`、`enum`、`LocalDate`…） | 经 `ParamValueConverters` 转换 |
| `String[]` / `List<String>` | 取 `request.getFullHeader(name)`（多值头） |

- 必填头缺失 → 抛异常 → `400`；`required=false` 且缺失 → 绑 `null`（或 `defaultValue`）。

```java
@Endpoint("/me")
@ResponseBody
public Object me(@RequestHeader("X-Trace-Id") String traceId,
                 @RequestHeader(value = "Accept", required = false) String accept) { ... }
```

> 桥接 Spring 的 `@RequestHeader`：`router.headerParamBy(org.springframework.web.bind.annotation.RequestHeader.class)`（其 `value()` / `required()` / `defaultValue()` 由框架反射读取，与内置注解一致）。

---

## 4. 消息转换

```java
public interface HttpMessageConverter {
    Object read(HttpRequest request, ConverterConfig config, Type type) throws Exception;
    void write(Object value, ConverterConfig config, HttpResponse response) throws Exception;
}
```

- `read`：处理 `@RequestBody`，`type` 为完整 `Type`（含泛型）。读取前可判断 `request.isStream()` 避免一次性读超大 body。
- `write`：处理 `@ResponseBody` 返回值。

通过 `.messageConverter(converter)` 启用（通常基于 Jackson / Gson / Fastjson 实现）：

```java
// 默认注册到 JSON（最常用）
router.messageConverter(new JacksonMessageConverter());

// 也可按产出类型注册不同 converter（XML / TEXT / HTML / CUSTOM 等）
router.messageConverter(ContentType.XML, new XmlMessageConverter());
```

> **`TEXT` 已内置**：构造 `AnnotationRouterHandler` 时即自动注册零依赖的 `TEXT` 转换器，声明 `responseType = ContentType.TEXT` 的 `@ResponseBody` 端点无需任何配置即可返回纯文本；用 `.messageConverter(ContentType.TEXT, ...)` 可覆盖默认实现。

`ConverterConfig` 是**每个端点一份**的转换配置，扫描期构建，字段含义：

| 方法 | 说明 |
|:-----|:-----|
| `responseType(ContentType)` / `getResponseType()` | 端点产出类型，由 `@Endpoint.responseType()` 填充 |
| `pretty(boolean)` / `isPretty()` | 是否美化输出（由具体 converter 读取） |
| `skipNull(boolean)` / `isSkipNull()` | 是否跳过 `null` 字段 |
| `dateFormat(String)` / `getDateFormat()` | 日期时间格式化模式 |
| `getResponseContentType()` | 完整响应 `Content-Type` 头值（含 UTF-8；`CUSTOM` 返回 `null`） |
| `isJson()` / `isTextual()` / `isCustom()` | 判断产出类型：JSON（默认）/ 文本类（XML·TEXT·HTML）/ 自定义 |


> 需要预设更多选项，可重写 `AnnotationRouterHandler.buildConverterConfig(MethodRouteInfo)`。

---

## 5. IoC 容器

内置 `BeanContainer` 是一个**轻量 IoC 容器**，扫描期构建，支持 `@Component`、`@Configuration` + `@Bean`、`@Inject`、`@Value`、`@PostConstruct`、`@PreDestroy`、按类型/名称查找，以及依赖延迟重试。

### 5.1 @Component

```java
@Component                 // value() 默认 ""，Bean 名 = 去首字母小写的简单类名
public class UserService { ... }
```

- 自动实例化并注册，可作为构造器依赖注入到控制器或其他组件。
- 注册要求：类须为 `public`、非抽象、非匿名类（`AnnotationRouteUtils.isEligibleClass`）。
- 同一实例**不能**以多个名字注册（1-to-1 约束）。

### 5.2 @Configuration + @Bean

```java
@Configuration            // 必须有无参构造器
public class AppConfig {
    @Bean                  // 名字默认 = 方法名；@Bean("name") 自定义
    public DataSource dataSource() { return ...; }

    @Bean
    public UserService userService(DataSource ds) {   // 参数按类型解析
        return new UserService(ds);
    }
}
```

约束与行为：

- **只有 `@Configuration` 类才被扫描 `@Bean` 方法**；普通 `@Component` 不支持 `@Bean`。
- `@Configuration` **必须有无参构造器**：构造器注入不支持，带参构造器会被**静默跳过**（连同其全部 `@Bean` 方法），并打印 warn 日志。
- `@Bean` 方法须为 `public` 非静态、返回类型非 `void` 且非基本类型（`void` / 基本类型无可用 Bean 类型，被忽略）。
- `@Bean` 结果按**名字**登记（`asFactory`），允许多个同类型实例；类标注的 `@Component` 为单例（按具体类型索引）。
- 方法参数按类型解析（构造器注入风格）。

### 5.3 构造器注入与 @Inject

```java
@Component
public class OrderService {
    private final UserService userService;
    @Inject                      // 也可用 @Inject 标注构造器（多个 @Inject 构造器 → 报错）
    public OrderService(UserService userService) {
        this.userService = userService;
    }
}
```

- `@Inject` 用于**字段或参数**：默认按类型查找；`value()` 指定名字（同名多 Bean 时）。
- 构造器选择：存在**恰好一个** `@Inject` 构造器 → 用它；否则取**第一个 public 构造器**。
- `@Component` / `@Controller` 实例在构造完成后做字段注入；`@Bean` 工厂结果**不参与** `ComponentEnhancer` 代理。

### 5.4 @Value 与配置来源

```java
@Component
public class UserService {
    @Value("${app.prefix:User-}")      // key 缺失且无默认 → 抛 IllegalStateException
    private String namePrefix;
}
```

占位符语法 `${key:default}`，解析优先级（高 → 低）：

1. **`-D` 系统属性**（最高优先级，覆盖一切）
2. **扫描配置**：`application.properties` 等配置文件（每次扫描/热重载重新读取）
3. **静态配置**：`.property(key, value)` / `.properties(map)` 程序化设置（热重载保留）

配置加载位置（同一 key 后者覆盖前者，优先级递增）：classpath 根 → classpath `/config` → jar 目录 → jar `/config` → 父级 `/config`。

```java
router.configFiles("application.properties", "app.properties"); // 默认 application.properties；无参调用禁用
router.ignoreInternalConfig(true);  // 忽略 jar 内配置，只加载外部文件（JAR 目录 / JAR /config / 父 /config）
```

> 配置文件变更会在热重载时生效（见第 9 节）。

### 5.5 生命周期 @PostConstruct / @PreDestroy

```java
@Component
public class Cache {
    @PostConstruct public void init() { ... }   // 实例化 + 字段注入完成后调用
    @PreDestroy   public void close() { ... }   // 容器清理（clearScan / clear）时调用
}
```

- 框架自带，无需 `javax.annotation-api` 依赖。
- `@PostConstruct` 在**全部字段注入之后**执行；`clearScan()`（热重载）会先触发 `@PreDestroy` 再重建。

### 5.6 扫描与依赖解析流程

`doScan()` 内部分阶段、顺序固定：

| 阶段 | 内容 |
|:-----|:-----|
| 0 | 注册 `BeanRegistrationHandler`（Registrar，见第 10.3 节），早于组件实例化 |
| 1 | 注册 `@Configuration`（`@Bean` 方法）+ `@Component`，依赖缺失最多**重试 5 次** |
| 2 | 注册控制器与 WebSocket 端点 |
| 3 | 注入所有字段（`@Value` / `@Inject`） |
| 4 | 执行 `@PostConstruct` |
| 5 | 通知 Registrar `onAllReady`（容器完全就绪） |
| 6 | 注册 `@Interceptor` 链 |

- **延迟重试**：构造/工厂方法依赖尚未就绪时进入 `deferred` 列表，重试至多 5 轮；仍无法解析则抛 `RuntimeException`（列出未决依赖）。
- 控制器 / `@Configuration` / `@Component` 均按**全限定类名（FQN）**登记，避免不同包的简名冲突。

---

## 6. 拦截器（注解注册）

`RouterInterceptor` 的完整机制（两种类型、短路语义、异常兜底、三种拦截器对比、停用开关）见 [http-interceptor-guide.md](http-interceptor-guide.md) 第 12 节。此处仅补充**注解驱动**用法。

```java
@Interceptor(value = "admin", order = 1, type = InterceptorType.ENDPOINT)
public class AdminAuthInterceptor implements RouterInterceptor {
    public boolean beforeHandle(String path, HttpRequest request, HttpResponse response) {
        if (!"admin".equals(request.getHeader("X-Role"))) {
            response.status(403).body("Forbidden");
            return false;     // 短路
        }
        return true;
    }
}
```

`@Interceptor` 属性：

| 属性 | 默认 | 说明 |
|:-----|:-----|:-----|
| `value()` | `""`（去首字母小写类名） | 引用名，也是 `@WithInterceptor` 的绑定键 |
| `order()` | `0` | 同链内执行顺序，越小越先 |
| `type()` | `PRE_ROUTE` | `PRE_ROUTE`=全局前置链；`ENDPOINT`=仅被 `@WithInterceptor` 引用时生效 |
| `disabled()` | `false` | `true` 时该拦截器彻底不执行（无论在全局链还是被引用） |

`@WithInterceptor` 绑定到**类级**（该类全部端点）或**方法级**（追加到类级之后），绑定在扫描期解析、去重、按 `order` 预排序：

```java
@RestController("/admin")
@WithInterceptor("admin")             // 类级
public class AdminController {
    @Endpoint("/users")
    public String users() { ... }

    @Endpoint("/audit")
    @WithInterceptor("audit")         // 方法级：admin 之后再追 audit
    public String audit() { ... }
}
```

| 类型 | 生效范围 |
|:-----|:---------|
| `PRE_ROUTE` | 全局链，路由分发前对该 router 全部请求生效 |
| `ENDPOINT` | 只对 `@WithInterceptor("name")` 引用的类/方法生效；未被引用则不执行 |

> **启动期校验（fail-fast）**：`@WithInterceptor` 引用的名字在注册控制器时立即解析；引用不存在的 Bean 名、或 Bean 未实现 `RouterInterceptor` → 直接抛异常。`PRE_ROUTE` 拦截器被 `@WithInterceptor` 引用会被**跳过**（已进全局链，避免重复）；类级与方法级引用同一 `ENDPOINT` 拦截器会自动去重。

---

## 7. SSE（@Sse）

```java
@Target(ElementType.METHOD)
public @interface Sse {
    String value();          // 端点路径
    long timeout() default -1; // ms；<0 回退全局 SSE_TIMEOUT_MS 选项；0 = 立即关闭
}
```

约束：

- 方法**必须含有恰好一个 `SseEmitter` 类型的参数**（其余参数如 `@PathParam` / `@RequestParam` / `HttpRequest` 照常可加，见下例），且**返回 `void`**，否则扫描期抛 `IllegalArgumentException`。
- **不能**与 `@Endpoint` 标在同一方法上（同一方法既 `@Endpoint` 又 `@Sse` → 注册失败）。
- 一个方法**不能同时**声明 `@RequestBody`（`@Sse` 产生流、不消费 body）。
- `@Sse` 复用 `@Endpoint` 的参数绑定管线（`@PathParam` / `@RequestParam` / `HttpRequest` 均可），拦截器在 emitter 写出响应头之前执行。

```java
@Controller("/stream")
public class SseCtrl {
    @Sse("/events")
    public void events(SseEmitter emitter) {
        // 在 worker 线程中通过 emitter.emit(...) 推送（方法名是 emit，不是 send）
        emitter.emit("hello");                                  // 仅 data
        emitter.emit("chat", "hi", "msg-001", 3000);            // event/data/id/retry 全字段
    }

    // 其他参数照常可加：SseEmitter 只要求“恰好一个该类型”，不限制方法参数总数
    @Sse("/rooms/{room}")
    public void room(SseEmitter emitter,
                     @PathParam("room") String room,
                     HttpRequest request) {
        // emitter + 路径变量 room + 原始请求，三者都有
        emitter.emit("joined: " + room);
    }
}
```

`SseEmitter` 实例由框架在流打开后注入（按参数类型识别，无需注解），可用方法：

| 方法 | 说明 |
|:-----|:-----|
| `emit(String data)` | 发送一条仅含 `data` 的事件 |
| `emit(String event, String data, String id, long retry)` | 全字段事件（null/0 的字段会被省略） |
| `close()` | 关闭连接、释放资源；可重复调用（幂等） |
| `onClose(Runnable)` / `isClosed()` | 关闭回调 / 是否已关闭 |

---

## 8. WebSocket（@WebSocket）

```java
@Target(ElementType.TYPE)
public @interface WebSocket {
    String value();   // 端点路径（相对 contextPath）
}
```

`@WebSocket` 注解本身**只有 `value()`（路径）**，没有 timeout / maxPayloadSize / allowedOrigins 等属性。这些能力全部由你继承的 `WebSocketResource` 基类提供，在端点类**无参构造器**里通过 `super(...)` 或流式 setter 配置（`registerWebSocketEndpoint` 用无参构造器实例化，见 `AnnotationRouterHandler.java:743`）。

- 标注在**继承 `WebSocketResource` 的类**上；扫描期实例化并注册为升级端点（自动追加 contextPath）。
- 支持 `@WithInterceptor` 绑定（`ENDPOINT` 拦截器）与 `@Value` / `@Inject` 注入（字段/构造器）。

### 8.1 配置项（WebSocketResource）

| 配置 | 设置方式 | 说明 |
|:-----|:---------|:-----|
| 广播 `broadcast` | `super(true/false)` 构造器 | 是否启用连接广播（影响 `broadcastMessage` / `disconnect` / `disconnectByAccount`） |
| 空闲超时 `timeout` | `super(int)` / `super(int, TimeoutStrategy)` | 秒；非正 = 无超时 |
| 超时策略 `timeoutStrategy` | `super(..., TimeoutStrategy)` | `DISCONNECT`（超时断开）/ `PING`（发 ping 保活） |
| 单条消息上限 `maxPayloadSize` | `.maxPayloadSize(int)` | 字节；覆盖全局 `HttpConf.MAX_WS_FRAME_SIZE` |
| 分片策略 `continuationStrategy` | `.continuationStrategy(ContinuationStrategy)` | `MERGE` / `BATCH` / `STREAM` |
| 续帧上限 `maxContinuations` | `.maxContinuations(int)` | 单条分片消息最大续帧数，超出发 1009 |
| 分片合并超时 `fragmentMergeTimeoutMs` | `.fragmentMergeTimeoutMs(long)` | ms，超出发 1009（MERGE/BATCH 生效） |
| Origin 白名单 `allowedOrigins` | `.allowedOrigins(String...)` | CSWSH 防护（RFC 6455 §10.2）；`"*"` = 允许任意 |
| 放行无 Origin | `.allowMissingOrigin(boolean)` | 是否放行无 Origin 头的请求（默认 true） |

> 前三项 `broadcast` / `timeout` / `timeoutStrategy` 只能经**构造器**传入；其余均为**流式 setter**，在构造器里 `this.xxx(...)` 链式调用即可。

### 8.2 握手与生命周期回调（override）

| 方法 | 触发时机 |
|:-----|:-----|
| `boolean beforeHandshake(HttpRequest, HttpResponse)` | 升级前校验；返回 false 拒绝连接 |
| `String getSubprotocols(String supported)` | 子协议协商（从客户端支持的列表里选一个返回） |
| `void onOpen(WebSocketConnection)` | 连接建立 |
| `void onMessage(WebSocketConnection, String)` | 收到文本消息 |
| `void onBinary(WebSocketConnection, byte[])` | 收到二进制消息 |
| `void onFrame(WebSocketConnection, WebSocketFrame)` | 每帧到达（含 PING/PONG/CLOSE） |
| `void onContinuation(WebSocketConnection, WebSocketFrame)` | 续帧（仅 BATCH/STREAM） |
| `void onClose(WebSocketConnection, int code, String reason)` | 正常关闭 |
| `void onError(WebSocketConnection, Throwable)` | 出错 |
| `void onErrorClose(WebSocketConnection)` | 异常关闭（如连接重置） |

`WebSocketConnection` 发送能力：`sendText(String)` / `sendBinary(byte[])` / `sendFile(File)` / `sendInputStream(InputStream)` / `push(WebSocketFrame)` / `close(int, String)` / `ping()` / `pong()`。

### 8.3 示例

```java
@WebSocket("/chat")
public class ChatEndpoint extends WebSocketResource {
    // 构造器传 broadcast/timeout/timeoutStrategy；其余用流式 setter
    public ChatEndpoint() {
        super(true, 60, TimeoutStrategy.PING);   // 广播开、60s 空闲超时、超时发 ping
        maxPayloadSize(1 << 20)                   // 单条消息上限 1MB
            .allowedOrigins("https://example.com"); // 仅允许该 Origin
    }

    @Override public void onOpen(WebSocketConnection conn) { /* ... */ }

    @Override public void onMessage(WebSocketConnection conn, String msg) {
        // 文本帧用 WebSocketFrame.textOf；广播给所有连接
        broadcastMessage(WebSocketFrame.textOf(msg));
    }

    @Override public boolean beforeHandshake(HttpRequest req, HttpResponse resp) {
        return req.getParameter("token") != null;   // 拒绝无 token 的升级请求
    }
}
```

---

## 9. 开发热重载

`DevHotReloader` 在**开发环境**（主类从目录而非 jar 加载）下自动启用，监听项目编译输出目录：

- **触发**：`.class` 文件变更（debounce 默认 `1000ms`，可用 `-Dwastnet.http.hot-reload.debounce=ms` 调整）；`configFiles` 指向的配置文件变更也会触发。
- **机制**：每次重载重建一个子 ClassLoader，只指向项目编译产物，库/框架类委托父加载器 → 重编译的 `.class` 从磁盘重新读取，注解身份保持稳定。
- **范围**：重载只清掉扫描产物（HTTP/SSE 路由、WebSocket、拦截器、扫描 Bean），保留手工注册的路由；`@PreDestroy` 先执行再重建。
- **排除**：框架自身目录被排除（重载它会破坏注解身份）；可用 `hotReloadWatchExclude("com.example.stable")` 排除稳定包。

```java
router.hotReload(false);                 // 关闭热重载（enabled=false 时不会重载，也无日志）
router.hotReload(true, false);           // 开启热重载但静音：第二个参数控制是否打印每次重载日志
router.hotReloadWatchExclude("com.example.stable");
```

```java
// 控制台输出示例
// [dev] hot reload: ReloadController.class changed, reloading...
// [dev] hot reload: OK ReloadController.class reloaded in 36 ms
```

> 重载失败会保留上一次状态并打印错误，不会让服务崩溃。

---

## 10. 可扩展点

### 10.1 AnnotationResolver 与 *By 桥接

`DefaultAnnotationResolver` 仅识别框架自带注解。通过 `AnnotationRouterHandler` 的 `*By(...)` 系列方法，可以把**第三方注解**（如 Spring）桥接进来，无需改业务代码：

| 方法 | 默认注解 | 桥接示例 |
|:-----|:--------|:--------|
| `requestBodyBy(Class...)` | `@RequestBody` | Spring `RequestBody` |
| `responseBodyBy(Class...)` | `@ResponseBody`、`@RestController` | Spring `ResponseBody` / `RestController` |
| `pathParamBy(Class...)` | `@PathParam` | Spring `PathVariable` |
| `requestParamBy(Class...)` | `@RequestParam` | Spring `RequestParam` |
| `valueBy(Class...)` | `@Value` | Spring `Value` |
| `injectBy(Class...)` | `@Inject` | Spring `Autowired` |
| `beanBy(Class...)` | `@Bean` | Spring `Bean` |
| `postConstructBy` / `preDestroyBy` | `@PostConstruct` / `@PreDestroy` | 其他生命周期注解 |

更彻底的定制可实现 `AnnotationResolver`（继承 `AnnotationFilter`）并 `.annotationResolver(resolver)` 注入，自行决定哪些类算控制器/组件、`@EnableXxx` 如何映射等。

```java
router.responseBodyBy(org.springframework.web.bind.annotation.ResponseBody.class,
                      org.springframework.web.bind.annotation.RestController.class);
```

### 10.2 ComponentEnhancer

代理钩子，作用于构造出的 `@Component` / `@Controller` 实例（`@Bean` 结果除外）：

```java
public interface ComponentEnhancer {
    default Class<? extends Annotation>[] proxyAnnotations() { return null; }
    default boolean requiresProxy(Class<?> componentClass) { return false; }
    Object enhance(Class<?> componentClass, Constructor<?> ctor, Object[] args);
}
```

- `proxyAnnotations()`：类或方法标了其中任一注解即触发代理。
- `requiresProxy(cls)`：自定义判定是否包装。
- `enhance(...)`：返回的对象**必须可赋值给** `componentClass`，否则注册失败。

```java
router.componentEnhancer(myCglibEnhancer);   // null = 关闭代理
```

### 10.3 BeanRegistrationHandler + @Registration

用于把**第三方 / 框架外部**的 Bean 注册进容器（例如对接某个库的内部组件），早于组件实例化：

```java
@Registration(value = EnableFoo.class, order = 10)   // 绑定到 @EnableFoo 开关
public class FooRegistrar implements BeanRegistrationHandler {
    public void onRegister(RegistrarContext ctx) {
        ctx.registerBean("foo", new Foo());         // 扫描配置已加载，组件尚未实例化
    }
    public void onAllReady(RegistrarContext ctx) { } // 容器就绪后（可选）
    public void onDestroy() { }                       // 热重载 / clear 时（可选）
}
```

- `@Registration.value()` 绑定一个 `@EnableXxx` 开关注解；只有它被 `router.enables(...)` 列入白名单，`onRegister` 才执行。
- 发现与排序：`enables(...)` 各注解取首包段作为扫描根，扫描候选举例按 `@Registration.order()` 升序（并列按类名），再依次 `onRegister`；组件实例化在**之后**，因此扫描出的 Bean 可被 `@Inject`。
- `RegistrarContext` 提供 `registerBean` / `getBean` / `getConfig` / `addProperty`，与 `BeanContainer` 解耦。

```java
router.enables(EnableFoo.class, EnableBar.class);   // 每次调用替换上一组白名单
```

---

## 11. API 速查表

### 11.1 AnnotationRouterHandler 配置方法

| 方法 | 说明 | 时机 |
|:-----|:-----|:-----|
| `scanPackages(String...)` | 设置扫描包；无参清空；不调用则回退主类所在包 | `start()` 前 |
| `messageConverter(HttpMessageConverter)` / `messageConverter(ContentType, HttpMessageConverter)` | 注册自动转换 converter（`TEXT` 已内置、可覆盖；其余类型可按产出类型分别注册，默认 JSON） | 任意 |
| `property(k, v)` / `properties(map)` | 程序化静态配置（热重载保留） | 任意 |
| `configFiles(String...)` | 扫描期加载的配置文件，默认 `application.properties` | `start()` 前 |
| `ignoreInternalConfig(boolean)` | 忽略 jar 内配置，只加载外部文件 | `start()` 前 |
| `hotReload(boolean)` / `hotReload(boolean, boolean)` | 开发热重载开关 / 是否打印控制台 | `start()` 前 |
| `hotReloadWatchExclude(String...)` | 热重载排除的包 | `start()` 前 |
| `enables(Class<? extends Annotation>...)` | 启用 `@EnableXxx` 开关（白名单） | 任意 |
| `componentEnhancer(ComponentEnhancer)` | 组件代理钩子（`null` 关闭） | 任意 |
| `annotationResolver(AnnotationResolver)` | 自定义注解解析器 | 任意 |
| `*By(Class...)` | 桥接第三方注解（Spring 等） | 任意 |
| `registerParamConverter(type, fn)` | 注册自定义参数类型转换器 | 任意 |

### 11.2 注解属性速查

| 注解 | 关键属性 | 默认值 |
|:-----|:---------|:-------|
| `@Controller` / `@RestController` | `value()`（base path） | `""` |
| `@Endpoint` | `value()`、`allowMethods()`、`responseType()` | **（必填，无默认值）** / `{}` / `JSON`；`value()` 空串或纯空白 → 该方法被跳过、不生成路由 |
| `@Sse` | `value()`、`timeout()` | — / `-1` |
| `@WebSocket` | `value()` | — |
| `@PathParam` | `value()`（变量名） | — |
| `@RequestParam` | `value()`、`required()`、`defaultValue()` | — / `true` / `""` |
| `@RequestHeader` | `value()`、`required()`、`defaultValue()` | — / `true` / `""` |
| `@RequestBody` | （无） | — |
| `@ResponseBody` | （无） | — |
| `@Component` | `value()`（Bean 名） | `""` |
| `@Configuration` | （无，须无参构造器） | — |
| `@Bean` | `value()`（Bean 名） | `""`（方法名） |
| `@Inject` | `value()`（名字，空=按类型） | `""` |
| `@Value` | `value()`（`${key:default}`） | — |
| `@Interceptor` | `value()`、`order()`、`type()`、`disabled()` | `""` / `0` / `PRE_ROUTE` / `false` |
| `@WithInterceptor` | `value()`（`String[]` 引用名） | `{}` |
| `@Registration` | `value()`（`@EnableXxx`）、`order()` | — / `Integer.MAX_VALUE` |
| `@PostConstruct` / `@PreDestroy` | （无） | — |

### 11.3 内置参数 / @Value 转换器

锁定（不可覆盖）：`String`、`boolean/Boolean`、`char/Character`、`byte/Byte`、`short/Short`、`int/Integer`、`long/Long`、`float/Float`、`double/Double`。

可覆盖：`BigDecimal`、`BigInteger`、`LocalDate`、`LocalDateTime`；任意 `enum` 通用支持；其余自定义类型须 `registerParamConverter`。

---

## 12. 常见问题与陷阱

1. **`@ResponseBody` 没反应 / 启动报错**：忘记 `.messageConverter(...)`。标了 `@ResponseBody` 但没配置 converter，控制器注册时直接抛异常。
2. **参数总是 null**：忘记加 `@PathParam` / `@RequestParam` / `@RequestBody`。非 `HttpRequest` / `HttpResponse` / `SseEmitter` 的参数必须带注解。
3. **`@Configuration` 的 `@Bean` 没生效**：类没有无参构造器 → 被静默跳过（看 warn 日志）。`@Bean` 方法须 `public`、非静态、返回非 `void` 非基本类型。
4. **`@Value` 报「Missing configuration」**：占位符没有默认值且 key 在 `-D` / 配置文件 / 静态配置中都不存在。要么补默认 `${k:def}`，要么用 `.property(...)` 或 `configFiles(...)` 提供。
5. **`@PathParam("x")` 注册失败**：路径里没有对应 `${x}` / `{x}` 变量（含正则）。
6. **`@Sse` 注册失败**：方法不是恰好一个 `SseEmitter` 参数，或返回非 `void`，或与 `@Endpoint` 同方法，或带了 `@RequestBody`。
7. **热重载不生效**：以 jar 方式运行（非开发目录加载）时热重载默认不启用；或包被 `hotReloadWatchExclude` 排除；或变更的不是 `.class` / 配置触发路径。
8. **同名多 Bean 注入报错**：按类型查找到多个 Bean 时抛 `IllegalStateException`，用 `@Inject("name")` 按名字限定。

---

## 13. 完整示例

完整的可运行示例见 `wastnet-test` 模块 `examples/http/mvc/`（`MvcDemo` 启动类）：包含 `@RequestBody`/`@ResponseBody` 自动转换、`@Value` 注入、拦截器、SSE、WebSocket。
