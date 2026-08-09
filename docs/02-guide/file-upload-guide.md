# 文件上传指南

wastnet 支持 `multipart/form-data` 格式的文件上传解析，自动在小字段（内存）和大文件（临时文件）之间切换，无需手动配置。

---

## 快速开始

```java
router.exactRoute("/upload", new HttpRoute() {
    public void handle(String path, HttpRequest request, HttpResponse response) throws Throwable {
        // 检查是否为 multipart 请求
        if (!request.isMultipart()) {
            response.status(400).body("Content-Type must be multipart/form-data");
            return;
        }

        // 遍历所有字段
        for (String fieldName : request.getMultipartFieldNames()) {
            MultipartField field = request.getMultipartField(fieldName);

            if (field.isFile()) {
                // 文件字段：保存到磁盘
                String originalName = field.getFileName();
                File targetFile = new File("/tmp/uploads", originalName);
                field.transferTo(targetFile);
                System.out.println("Saved: " + originalName);
            } else {
                // 普通表单字段
                String value = field.getDataAsString();
                System.out.println(fieldName + " = " + value);
            }
        }

        response.body("Upload OK".getBytes());
    }
});
```

---

## MultipartField API

`MultipartField` 是文件上传字段的抽象基类，提供统一的读取接口：

| 方法 | 返回类型 | 说明 |
|:-----|:---------|:------|
| `getName()` | `String` | 字段名称 |
| `isFile()` | `boolean` | 是否为文件上传字段 |
| `getFileName()` | `String` | 原始文件名（非文件字段返回 null） |
| `getContentType()` | `String` | 字段的 Content-Type |
| `getData()` | `byte[]` | 字段数据（大文件字段会抛出异常，见下文） |
| `getInputStream()` | `InputStream` | 读取字段数据的输入流（大文件推荐） |
| `transferTo(File)` | `void` | 将字段数据写入文件（仅文件字段可用） |
| `transferTo(File, boolean)` | `void` | 写入文件，支持追加模式 |
| `getDataAsString()` | `String` | 字段数据转为 UTF-8 字符串 |
| `getDataAsString(Charset)` | `String` | 字段数据转为指定编码字符串 |
| `isTempFile()` | `boolean` | 是否由临时文件备份（大文件字段） |

### 大文件字段的特殊处理

当上传的文件超过内存阈值时，框架会自动将数据写入临时文件（`MultipartFieldFile`）。此时：

- **`getData()` 会抛出 `UnsupportedOperationException`**，提示改用 `getInputStream()` 或 `transferTo()`
- **推荐使用 `transferTo(File)`**：零拷贝写入目标文件，不占用 JVM 堆内存
- 临时文件会在请求处理完成后自动清理

```java
// 大文件推荐方式
MultipartField field = request.getMultipartField("file");
field.transferTo(new File("/dest/file.zip"));  // 直接写入，不经过堆内存

// 或使用流式读取
InputStream in = field.getInputStream();
// ... 自行处理流
```

---

## HttpRequest 中的 multipart 方法

| 方法 | 说明 |
|:-----|:------|
| `isMultipart()` | 检查请求是否为 multipart/form-data |
| `getMultipartField(String name)` | 获取指定名称的字段 |
| `getMultipartFields(String name)` | 获取指定名称的所有字段（支持同名多文件） |
| `getMultipartFieldValue(String name)` | 获取字段值字符串（仅非文件字段） |
| `getMultipartFieldValues(String name)` | 获取字段值字符串列表 |
| `getMultipartFieldNames()` | 获取所有字段名称 |

### 同名多文件上传

```java
// 处理多个同名文件（如 <input type="file" name="photos" multiple>）
List<MultipartField> photos = request.getMultipartFields("photos");
for (MultipartField photo : photos) {
    if (photo.isFile()) {
        photo.transferTo(new File("/uploads/photo.bin"));
    }
}
```

---

## 配置项

文件上传相关配置通过系统属性设置：

| 属性 | 默认值 | 说明 |
|:-----|:------:|:------|
| `wastnet.http.max-body-in-memory` | 2MB | **请求 Body 内存上限**：超过此值转为流式处理，避免完整加载到内存 |
| `wastnet.http.body-max-size` | 512MB | **请求 Body 最大总大小**（字节）：全局请求体大小限制，适用于所有请求（含上传），非正值表示无限制，支持 KB/MB/GB 单位 |
| `wastnet.http.enable-temp-file` | true | **是否启用临时文件**：关闭时将跳过需要临时文件的上传字段 |
| `wastnet.http.temp-file-dir` | 系统临时目录 | **临时文件目录**：自定义上传文件的临时存储位置 |

示例：

```java
// 限制上传最大 100MB，设置临时文件目录
System.setProperty("wastnet.http.body-max-size", "100MB");
System.setProperty("wastnet.http.max-body-in-memory", "5MB");  // 5MB 以上转为流式
System.setProperty("wastnet.http.temp-file-dir", "/data/upload-tmp");
```

---

## 完整示例

参见测试项目中的 `FileUploadDownloadTest.java`（`wastnet-test/src/main/java/io/github/wycst/wastnet/examples/http/FileUploadDownloadTest.java`），它是一个完整的 HTTP + HTTPS 文件上传 / 下载服务示例，包含：

- POST `/upload` 端点处理文件上传
- 自动加载的上传表单页面
- 多文件字段支持
- 文件重名处理
- 上传耗时统计

使用 curl 测试：

```bash
# 上传单个文件
curl -X POST -F "file=@/path/to/photo.jpg" http://localhost:8080/upload

# 上传多个文件 + 表单字段
curl -X POST \
  -F "file1=@/path/to/doc1.pdf" \
  -F "file2=@/path/to/doc2.pdf" \
  -F "description=Project documents" \
  http://localhost:8080/upload
```
