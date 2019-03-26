package com.ls.spring.framework.v1;

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

public class MyDispacherServlet extends HttpServlet {

    // 配置资源文件
    private Properties contextConfig = new Properties();
    // 保存扫描到了所有类名
    private List<String> classNames = new ArrayList<>();
    // Ioc容器
    private Map<String, Object> iocMap = new HashMap<>();
    // url与方法的映射
    private Map<String, Method> handlerMapping = new HashMap<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        // 调用
        try {
            doDispach(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 分发请求
    private void doDispach(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        // 绝对路径处理为相对路径
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        uri = uri.replaceAll(contextPath, "").replaceAll("/+", "/");

        if (!handlerMapping.containsKey(uri)) {
            resp.getWriter().write("404 Not Found!");
            return;
        }

        // 获取当前方法，并获取当前类
        Method method = handlerMapping.get(uri);
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        // 请求参数Map
        Map<String, String[]> parameterMap = req.getParameterMap();
        // 获取形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        // 实参列表
        Object[] params = new Object[parameterTypes.length];
        // 参数注解
        Annotation[][] paramAnnos = method.getParameterAnnotations();

        // 封装参数
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class) {
                params[i] = req;
                continue;
            } else if (parameterType == HttpServletResponse.class) {
                params[i] = resp;
                continue;
            }

            // 其他参数根据注解的值获取实参
            for (Annotation[] paramAnno : paramAnnos) {
                for (Annotation a : paramAnno) {
                    if (a instanceof MyRequestParam) {
                        String value = ((MyRequestParam) a).value();
                        String[] paramValues = parameterMap.get(value);
                        if (paramValues != null) {
                            String paramValue = Arrays.toString(paramValues)
                                    .replaceAll("\\[|\\]", "")
                                    .replaceAll("\\s", "");
                            params[i] = converter(parameterType, paramValue);
                        }
                    }
                }
            }
        }

        method.invoke(iocMap.get(beanName), params);
    }

    // 数据类型转换
    private Object converter(Class<?> paramType, String paramValue) {
        if (paramType == Integer.class) {
            return Integer.valueOf(paramValue);
        }
        return paramValue;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
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
                handlerMapping.put(url, method);
                System.out.println("Mapped:" + url + "," + method);
            }
        }

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

}
