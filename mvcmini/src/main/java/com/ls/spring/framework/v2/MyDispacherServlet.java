package com.ls.spring.framework.v2;

import com.ls.spring.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyDispacherServlet extends HttpServlet {

    // 配置资源文件
    private Properties contextConfig = new Properties();
    // 保存扫描到了所有类名
    private List<String> classNames = new ArrayList<>();
    // Ioc容器
    private Map<String, Object> iocMap = new HashMap<>();
    // url与方法的映射
    private List<Handler> handlerMapping = new ArrayList<>();

    @Override
    public void init(ServletConfig config) {
        // 1.加载配置文件
        doLoadConfig(config);

        // 2.扫描类
        doScan(contextConfig.getProperty("package-scan"));

        // 3.初始化IOC容器，生成扫描的类的实例，放入到IOC容器中
        doInstance();

        // 4.依赖注入
        doAutowired();

        // 5.初始化HandlerMapping
        initHandlerMapping();

        System.out.println("初始化完成！");
    }

    // 加载配置文件
    private void doLoadConfig(ServletConfig config) {
        String contextConfigLocation = config.getInitParameter("contextConfigLocation");
        // 从Spring的主内存中读取
        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation)) {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 扫描类
    private void doScan(String sanPackage) {
        // 将包路径转换为系统路径，找到目录下的class文件，获取ClassName，并使用反射实例化扫描到的类
        URL url = this.getClass().getClassLoader().getResource("/" + sanPackage.replaceAll("\\.", "/"));
        File classpath = new File(url.getFile());
        // 循环classpath下的文件，如果是文件夹则继续递归打开子文件夹
        for (File file : classpath.listFiles()) {
            if (file.isDirectory()) {
                doScan(sanPackage + "." + file.getName());
            } else {
                classNames.add(sanPackage + "." + file.getName().replaceAll(".class", ""));
            }
        }
    }

    // 将有注解标注的类实例化，方法ioc容器中
    private void doInstance() {
        if (classNames.size() == 0) {
            return;
        }

        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                // 有注解的类才初始化
                if (clazz.isAnnotationPresent(MyController.class)) {
                    Object instance = clazz.newInstance();
                    iocMap.put(toLowerFirstCase(clazz.getSimpleName()), instance);
                } else if (clazz.isAnnotationPresent(MyService.class)) {
                    Object instance = clazz.newInstance();
                    // 先判断注解中是否有value
                    MyService serviceAno = clazz.getAnnotation(MyService.class);
                    String beanName = serviceAno.value();
                    if ("".equals(beanName)) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    iocMap.put(beanName, instance);
                    // 接口注入，将本类的所有接口保存一份本类的实例
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (iocMap.containsKey(toLowerFirstCase(i.getName()))) {
                            throw new RuntimeException("当前接口已初始化实例！");
                        }
                        iocMap.put(toLowerFirstCase(i.getSimpleName()), instance);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 依赖注入
    private void doAutowired() {
        if (iocMap.isEmpty()) {
            return;
        }
        // 遍历ico容器，得到每一个实例的字段，检查是否有MyAutowire注解，如果有则注入
        for (Map.Entry<String, Object> iocEntry : iocMap.entrySet()) {
            Field[] fields = iocEntry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(MyAutowired.class)) {
                    continue;
                }
                // 获取有MyAutowire注解的字段，对应的实例
                MyAutowired autowiredAnno = field.getAnnotation(MyAutowired.class);
                String beanName = autowiredAnno.value();
                if ("".equals(beanName)) {
                    beanName = toLowerFirstCase(field.getType().getName());
                }

                field.setAccessible(true);

                try {
                    field.set(iocEntry.getValue(), iocMap.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    // 初始化HandlerMapping容器
    private void initHandlerMapping() {
        if (iocMap.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> iocEntry : iocMap.entrySet()) {
            // 没有打controller注解，则跳过
            Class<?> clazz = iocEntry.getValue().getClass();
            if (!clazz.isAnnotationPresent(MyController.class)) {
                continue;
            }

            // 获取类上的url地址
            String baseUrl = "";
            if (clazz.isAnnotationPresent(MyResquestMapping.class)) {
                baseUrl = clazz.getAnnotation(MyResquestMapping.class).value();
            }

            // 获取所有的方法
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if (!method.isAnnotationPresent(MyResquestMapping.class)) {
                    continue;
                }
                String url = method.getAnnotation(MyResquestMapping.class).value();
                url = ("/" + baseUrl + "/" + url).replaceAll("/+", "/");
                // 预编译正则匹配，提高运行时匹配性能
                Pattern pattern = Pattern.compile(url);
                handlerMapping.add(new Handler(pattern, method, iocEntry.getValue()));
                System.out.println("Mapped:" + url + "," + method);
            }
        }

    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // 调用
        try {
            doDispach(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exception,Detail:" + Arrays.toString(e.getStackTrace()));
        }
    }

    // 分发请求
    private void doDispach(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Handler handler = getHandler(req);
        // 获取请求中的参数
        Map<String, String[]> parameterMap = req.getParameterMap();
        // 实参数组
        Object[] params = new Object[handler.paramTypes.length];
        // 处理参数值
        for (Map.Entry<String, String[]> paramEntry : parameterMap.entrySet()) {
            // 获取参数名
            String name = paramEntry.getKey();
            if (!handler.paramIndexMapping.containsKey(name)) {
                continue;
            }
            // 根据参数名获取形参位置
            Integer index = handler.paramIndexMapping.get(name);
            // 参数值
            String paramValue = Arrays.toString(paramEntry.getValue())
                    .replaceAll("\\[|\\]", "")
                    .replaceAll("\\s", "");
            params[index] = converter(handler.paramTypes[index], paramValue);
        }

        // 处理request和response
        String requestName = HttpServletRequest.class.getName();
        if (handler.paramIndexMapping.containsKey(requestName)) {
            params[handler.paramIndexMapping.get(requestName)] = req;
        }
        String responseName = HttpServletResponse.class.getName();
        if (handler.paramIndexMapping.containsKey(responseName)) {
            params[handler.paramIndexMapping.get(responseName)] = resp;
        }

        // 调用方法
        Object returnValue = handler.method.invoke(handler.controller, params);
        if (returnValue == null || returnValue instanceof Void) {
            return;
        }
        resp.getWriter().write(returnValue.toString());
    }

    // 根据url获取handler
    private Handler getHandler(HttpServletRequest req) {
        // 绝对路径处理为相对路径
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        uri = uri.replaceAll(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : handlerMapping) {
            Matcher matcher = handler.pattern.matcher(uri);
            if (matcher.matches()) {
                return handler;
            }
        }
        return null;
    }

    // 数据类型转换
    private Object converter(Class<?> paramType, String paramValue) {
        if (paramType == Integer.class) {
            return Integer.valueOf(paramValue);
        }
        return paramValue;
    }

    // 将首字母小写
    private String toLowerFirstCase(String className) {
        char[] chars = className.toCharArray();
        if (chars[0] >= 65 && chars[0] <= 90) {
            chars[0] += 32;
            return String.valueOf(chars);
        } else {
            return className;
        }
    }

    // 在init中初始化方法信息，避免运行时反射，提高性能
    private class Handler {
        private Pattern pattern;
        private Method method;
        private Object controller;
        private Class[] paramTypes;
        private Map<String, Integer> paramIndexMapping;

        Handler(Pattern pattern, Method method, Object controller) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;

            paramTypes = method.getParameterTypes();
            paramIndexMapping = new HashMap<>();
            putParamIndexMapping();
        }

        // 将参数名（注解中的value）与参数下标关联
        private void putParamIndexMapping() {
            // request和response直接放入参数名
            for (int i = 0; i < paramTypes.length; i++) {
                Class paramType = paramTypes[i];
                if (paramType == HttpServletRequest.class
                        || paramType == HttpServletResponse.class) {
                    paramIndexMapping.put(paramType.getName(), i);
                }
            }

            // 根据注解，对应参数和顺序
            Annotation[][] annos = this.method.getParameterAnnotations();
            for (int i = 0; i < annos.length; i++) {
                for (Annotation a : annos[i]) {
                    if (a instanceof MyRequestParam) {
                        String name = ((MyRequestParam) a).value();
                        paramIndexMapping.put(name, i);
                    }
                }
            }
        }
    }

}
