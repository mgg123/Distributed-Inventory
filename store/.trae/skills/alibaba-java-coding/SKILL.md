---
name: "alibaba-java-coding"
description: "阿里巴巴Java开发手册编程规范。在编写Java代码、审查Java代码、或用户提到代码规范/编程规范/阿里巴巴规范时调用此skill。"
---

# 阿里巴巴Java开发手册编程规范

本 skill 基于《阿里巴巴Java开发手册》提炼核心编程规范，适用于代码编写、Code Review、静态检查等场景。

---

## 一、命名规范

### 1.1 通用命名
| 类型 | 规则 | 正例 | 反例 |
|------|------|------|------|
| 类名 | UpperCamelCase | `UserService`, `OrderDTO` | `userservice`, `order_dto` |
| 方法名/参数名/变量名 | lowerCamelCase | `getUserById`, `orderList` | `GetUserById`, `order_list` |
| 常量 | 全大写 + 下划线 | `MAX_RETRY_COUNT` | `maxRetryCount` |
| 包名 | 全小写，点分隔 | `com.mgg.exp.store` | `com.mgg.exp.Store` |
| 抽象类 | Abstract 或 Base 开头 | `AbstractService`, `BaseRepository` | - |
| 异常类 | Exception 结尾 | `InventoryException` | - |
| 测试类 | 被测试类名 + Test 结尾 | `UserServiceTest` | - |
| 枚举类 | Enum 后缀 | `OrderStatusEnum` | - |
| 接口 | 不加 I 前缀 | `InventoryService` | `IInventoryService` |
| 实现类 | 接口名 + Impl 后缀 | `InventoryServiceImpl` | - |

### 1.2 POJO 类中布尔变量的命名
- 禁止 `isXxx` 布尔属性（部分框架解析会引起序列化问题）
- 正例: `deleted`, `enabled`

### 1.3 Service/DAO 层方法命名
| 操作 | 前缀 | 示例 |
|------|------|------|
| 新增 | `save` / `insert` | `saveOrder()` |
| 删除 | `remove` / `delete` | `removeById()` |
| 更新 | `update` | `updateInventory()` |
| 获取单个 | `get` | `getBySkuId()` |
| 列表查询（不分页） | `list` | `listByStatus()` |
| 分页查询 | `page` / `query` | `pageOrders()` |
| 统计 | `count` | `countPending()` |

---

## 二、代码格式

### 2.1 缩进与换行
- 使用 **4个空格** 缩进，禁止 Tab
- 单行字符数限制 **不超过 120 个**
- 运算符与下文一起换行
- 方法参数在定义和传入时，多个参数逗号后必须加空格

### 2.2 大括号
- 左大括号不换行，右大括号换行
- `if/for/while/do` 等保留字与括号之间必须加空格
- 即使只有一行代码也必须使用大括号

```java
// 正例
if (condition) {
    doSomething();
}

// 反例
if (condition) doSomething();
```

### 2.3 空行与空格
- 方法之间用空行分隔
- 不同逻辑块之间用空行分隔
- 关键字与括号之间加空格（`if (`, `for (`, `while (`, `switch (`）
- 任何二目/三目运算符左右加空格
- 注释的双斜线与内容之间有一个空格

---

## 三、OOP 规约

### 3.1 访问控制
- 类成员与方法访问控制从严：能 `private` 就不要 `package`，能 `package` 就不要 `protected`，能 `protected` 就不要 `public`
- 对外暴露的接口签名，原则上不允许修改，`@Deprecated` 时必须说明新接口

### 3.2 对象比较
- 所有相同类型的包装类对象之间的值比较，全部使用 `equals`
- `BigDecimal` 必须使用 `compareTo` 而非 `equals`（因为 1.0 与 1.00 的 equals 结果为 false）
- Object 的 equals 容易抛空指针，应使用常量或有确定值的对象调用：`"test".equals(obj)`

### 3.3 POJO 规范
- POJO 类必须写 `toString` 方法
- 禁止在 POJO 中同时存在 `isXxx()` 和 `getXxx()` （框架调用歧义）
- 构造方法禁止加入业务逻辑；如有初始化逻辑请放在 `init()` 方法
- 所有 POJO 类属性必须使用包装类型，RPC 方法的返回值和参数必须使用包装类型

### 3.4 接口规范
- 接口过时必须加 `@Deprecated`，并说明新接口
- 接口方法签名确定后不允许随意增减参数

---

## 四、集合处理

### 4.1 集合初始化
- 集合初始化时，指定集合初始值大小：`new HashMap<>(16)`
- 使用 `Arrays.asList()` 转换的集合不可修改

### 4.2 集合操作
- 不要在 foreach 循环里进行元素的 `remove/add`，使用 `Iterator`
- 使用 `entrySet` 遍历 Map 而非 `keySet`+`get`（避免 N+1 查询）
- `Collections.emptyList()` 返回不可变集合，不要对其做修改操作
- 集合转数组使用 `list.toArray(new String[0])`

### 4.3 判空
- 判断所有集合内部元素是否为空使用 `isEmpty()`：`if (collection.isEmpty())`
- 高度注意 Map 集合 `K/V` 能否存储 null 值：
  - `HashMap`: K/V 均可 null
  - `ConcurrentHashMap`: K/V 均不可 null
  - `TreeMap`: K 不可 null, V 可 null

---

## 五、并发处理

### 5.1 线程池
- 线程池**不允许使用 Executors 创建**，通过 `ThreadPoolExecutor` 创建（明确线程池参数含义和运行规则）
- `SimpleDateFormat` 是线程不安全的，使用 `DateTimeFormatter` 代替

### 5.2 锁机制
- 高并发时，同步调用应考虑锁的粒度，能用无锁数据结构就不要用锁，能锁代码块就不要锁方法
- 使用 `synchronized` 时，避免使用 `String` 作为锁对象（String 常量池问题）
- 在使用阻塞等待获取锁的方式中，必须在 try 代码块之外，且在加锁方法与 try 代码块之间没有任何可能抛出异常的方法调用，避免加锁成功后，在 finally 中无法解锁

```java
// 正例
Lock lock = new ReentrantLock();
lock.lock();
try {
    doSomething();
} finally {
    lock.unlock();
}
```

### 5.3 并发集合
- `HashMap` 在并发场景下可能导致 CPU 100%，使用 `ConcurrentHashMap`
- 对多个资源、数据库表、对象同时加锁时，需要保持一致的加锁顺序，避免死锁

---

## 六、控制语句

### 6.1 条件判断
- 在 `if/else/for/while/do` 语句中**必须使用大括号**
- 表达异常分支时，少用 `if-else`，推荐卫语句（Guard Clause）：

```java
// 正例 - 卫语句
if (param == null) {
    throw new IllegalArgumentException("param must not be null");
}
if (param.isEmpty()) {
    return defaultValue;
}
doSomething(param);
```

### 6.2 switch 语句
- switch 必须有 `default` 分支，即使什么都不做
- 每个 `case` 要么以 `break/return` 结束，要么注释说明 fall-through

### 6.3 循环
- 循环体内字符串拼接使用 `StringBuilder`
- 避免在循环中频繁创建对象

---

## 七、注释规约

- 类、类属性、类方法必须有 Javadoc 注释（使用 `/** 内容 */`）
- 所有抽象方法必须用 Javadoc 注释
- 注释应说明代码是"做什么"的（what），而非"怎么做"的（how）
- 单行注释 `//` 与内容之间有一个空格
- 待办事项标记: `// TODO: 描述` 或 `// FIXME: 描述`

---

## 八、异常处理

### 8.1 异常捕获
- 不要捕获 Java 类库中定义的继承自 `RuntimeException` 的运行时异常类，如 `NullPointerException`（应预判空）
- 异常不要用来做流程控制、条件控制
- catch 时请分清稳定代码和非稳定代码，对非稳定代码的 catch 尽可能区分异常类型

### 8.2 异常抛出
- 事务场景中，catch 住异常后如果需要回滚，一定要手动回滚
- finally 块必须对资源对象、流对象进行关闭，有异常也要 try-catch
- 不要在 finally 块中使用 return
- 捕获异常与抛异常，必须是完全匹配，或者捕获异常是抛异常的父类

---

## 九、日志

- 应用中不可直接使用日志系统（Log4j、Logback）中的 API，应使用 SLF4J 门面
- 使用 `{}` 占位符拼接日志，禁止使用字符串拼接：`log.info("order: {}", orderId)`
- 敏感信息（密码、手机号、身份证等）禁止日志输出

```java
// 正例
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger log = LoggerFactory.getLogger(Xxx.class);
log.info("deduct inventory, skuId: {}, quantity: {}", skuId, quantity);
```

---

## 十、MySQL 数据库

### 10.1 建表规约
- 表名、字段名必须使用**小写字母+下划线**：`inventory_record`, `sku_id`
- 表名**不加复数**，禁止数字开头
- 主键索引名: `pk_字段名`；唯一索引名: `uk_字段名`；普通索引名: `idx_字段名`
- `varchar` 是可变长字符串，长度禁止超过 5000
- 表必备三字段: `id`, `create_time`, `update_time`

### 10.2 字段类型
- 小数类型使用 `decimal`，禁止 `float`/`double`（精度丢失）
- 库存数量等精确数值用 `decimal` 或 `bigint`（以分为单位存储金额）
- `varchar` 长度设计需根据实际需要进行适度冗余，但禁止过长

### 10.3 SQL 编写
- 不要使用 `count(列名)` 或 `count(常量)` 替代 `count(*)`（`count(*)` 是 SQL92 标准统计行数）
- `sum(col)` 结果为 null 时，NPE 风险，使用 `COALESCE(sum(col), 0)`
- 禁止使用存储过程，难以调试和扩展
- 数据订正/删除时先 SELECT 确认，避免误操作
- `in` 操作能避免则避免，集合元素控制在 1000 以内
- 表关联尽量使用 `inner join`，少用子查询

### 10.4 ORM
- 查询大表时，避免返回全部字段（`select *`）
- `@Transactional` 不要滥用，事务会影响 QPS；仅用于写操作
- 不允许直接使用 `BeanUtils` 进行 Entity/DTO/VO 之间的属性拷贝
- 更新数据表时，必须同时更新 `update_time` 字段

---

## 十一、分层架构

### 11.1 工程结构推荐
```
com.mgg.exp.store
├── controller      # HTTP 接口层
├── service         # 业务逻辑层
│   └── impl
├── repository      # 数据访问层 (或 mapper for MyBatis)
├── model           # 数据模型
│   ├── entity      # 数据库实体
│   ├── dto         # 数据传输对象
│   └── vo          # 视图对象
├── config          # 配置类
├── constant        # 常量定义
├── enums           # 枚举
├── exception       # 自定义异常
└── util            # 工具类
```

### 11.2 各层规范
- **Controller**: 仅做参数校验、调用 Service、封装返回结果，不包含业务逻辑
- **Service**: 业务逻辑层，优先设计接口；一个 Service 不要处理过多逻辑，可拆分
- **Repository/Mapper**: 仅做数据访问，不包含业务逻辑，SQL 与业务解耦
- **分层隔离**: 上层可依赖下层，下层不可反向依赖上层，同层可互相调用

---

## 十二、安全规约

- 用户请求传入的任何参数必须做有效性校验（`@Valid` / `@Validated`）
- 禁止向 HTML 页面输出未经安全过滤的用户数据
- SQL 参数化查询，禁止拼接 SQL 字符串（防止注入）
- 敏感数据（密码、密钥等）禁止硬编码，必须使用配置中心或环境变量

---

## 十三、应用示例

### 13.1 new HashMap 建议设置初始容量

当代码中使用 `new HashMap` 时，应提醒：

> `HashMap(int initialCapacity)` 建议指定初始容量，避免频繁扩容。
> 公式: `initialCapacity = (int) (expectedSize / 0.75f + 1)`

### 13.2 switch 语句检查

当发现 switch 语句缺少 `default` 时，应提醒补充。

### 13.3 Controller 职责检查

当 Controller 方法中包含复杂业务逻辑时，应提醒抽取到 Service 层。

### 13.4 日志字符串拼接检查

当发现以下反例时，应提醒修改为占位符模式：

```java
// 反例
log.info("user: " + user.toString());

// 正例
log.info("user: {}", user);
```

---

## 参考来源

- 《阿里巴巴Java开发手册（泰山版）》
- 阿里巴巴 Java 开发规约插件: https://github.com/alibaba/p3c
