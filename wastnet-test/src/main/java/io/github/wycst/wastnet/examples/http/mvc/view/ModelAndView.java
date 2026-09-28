package io.github.wycst.wastnet.examples.http.mvc.view;

import java.util.HashMap;
import java.util.Map;

/**
 * 视图名 + 模型载体，配合 {@link FreeMarkerViewResolver} 渲染。
 */
public class ModelAndView {

    private final String viewName;
    private final Map<String, Object> model = new HashMap<>();

    public ModelAndView(String viewName) {
        this.viewName = viewName;
    }

    /** 往模型里放一个键值对，返回自身便于链式调用。 */
    public ModelAndView add(String key, Object value) {
        model.put(key, value);
        return this;
    }

    public String getViewName() {
        return viewName;
    }

    public Map<String, Object> getModel() {
        return model;
    }
}
