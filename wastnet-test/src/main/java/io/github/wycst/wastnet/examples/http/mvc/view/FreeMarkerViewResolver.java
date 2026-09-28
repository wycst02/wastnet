package io.github.wycst.wastnet.examples.http.mvc.view;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import io.github.wycst.wastnet.http.HttpRequest;
import io.github.wycst.wastnet.http.HttpResponse;
import io.github.wycst.wastnet.http.annotation.ViewResolver;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/**
 * 基于 FreeMarker 的视图解析器：仅匹配 {@link ModelAndView} 返回值（避免误吞返回裸 String 的普通接口）。
 * 模板从 classpath 的 /templates 下加载，渲染结果以 text/html 写回响应。
 */
public class FreeMarkerViewResolver implements ViewResolver {

    private final Configuration configuration;

    public FreeMarkerViewResolver() {
        configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setClassForTemplateLoading(this.getClass(), "/templates");
        configuration.setDefaultEncoding("UTF-8");
    }

    @Override
    public boolean supports(Class<?> returnType) {
        return ModelAndView.class.isAssignableFrom(returnType);
    }

    @Override
    public void render(Object returnValue, HttpRequest request, HttpResponse response) throws Exception {
        ModelAndView mv = (ModelAndView) returnValue;
        String viewName = mv.getViewName();
        Map<String, Object> model = mv.getModel();
        Template template = configuration.getTemplate(viewName);
        StringWriter writer = new StringWriter();
        try {
            template.process(model, writer);
        } catch (TemplateException e) {
            throw new IOException("FreeMarker render failed: " + viewName, e);
        }
        response.contentType("text/html;charset=utf-8").body(writer.toString());
    }
}
