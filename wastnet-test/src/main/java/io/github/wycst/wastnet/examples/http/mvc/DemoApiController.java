package io.github.wycst.wastnet.examples.http.mvc;

import io.github.wycst.wastnet.http.HttpMethod;
import io.github.wycst.wastnet.http.MultipartField;
import io.github.wycst.wastnet.http.annotation.Controller;
import io.github.wycst.wastnet.http.annotation.Endpoint;
import io.github.wycst.wastnet.http.annotation.PathParam;
import io.github.wycst.wastnet.http.annotation.RequestParam;
import io.github.wycst.wastnet.http.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

/**
 * 演示注解路由中路径变量、@RequestParam 与文件上传(MultipartField) 的用法。
 */
@Controller("/demo")
public class DemoApiController {

    // 路径变量：wastnet 自有写法 ${id}
    @Endpoint("/user/${id}")
    @ResponseBody
    public Object pathVarWycst(@PathParam("id") long id) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("syntax", "${id}");
        map.put("id", id);
        return map;
    }

    // 路径变量：Spring 风格写法 {uid}
    @Endpoint("/profile/{uid}")
    @ResponseBody
    public Object pathVarSpring(@PathParam("uid") long uid) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("syntax", "{uid}");
        map.put("uid", uid);
        return map;
    }

    // @RequestParam 标量：required + 默认值（page/size 缺省时有默认）
    @Endpoint("/search")
    @ResponseBody
    public Object requestParam(@RequestParam("q") String q,
                               @RequestParam(value = "page", required = false, defaultValue = "1") int page,
                               @RequestParam(value = "size", required = false, defaultValue = "10") int size) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("q", q);
        map.put("page", page);
        map.put("size", size);
        return map;
    }

    // @RequestParam 同名多值：?tag=a&tag=b
    @Endpoint("/tags")
    @ResponseBody
    public Object requestParamMulti(@RequestParam("tag") String[] tags) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("tags", tags);
        return map;
    }

    // 单文件上传：类型 MultipartField，未上传时为 null
    @Endpoint(value = "/upload", allowMethods = HttpMethod.POST)
    @ResponseBody
    public Object uploadSingle(@RequestParam("file") MultipartField file) {
        Map<String, Object> map = new HashMap<String, Object>();
        if (file != null && file.isFile()) {
            map.put("fileName", file.getFileName());
            map.put("size", file.size());
            map.put("contentType", file.getContentType());
        } else {
            map.put("error", "no file uploaded");
        }
        return map;
    }

    // 多文件上传：MultipartField[]，并组合路径变量 + 普通参数
    @Endpoint(value = "/upload/${dir}", allowMethods = HttpMethod.POST)
    @ResponseBody
    public Object uploadMulti(@PathParam("dir") String dir,
                              @RequestParam("files") MultipartField[] files,
                              @RequestParam(value = "desc", required = false) String desc) {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("dir", dir);
        map.put("desc", desc);
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; ++i) names[i] = files[i].getFileName();
        map.put("files", names);
        return map;
    }
}
