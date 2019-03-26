package com.ls.spring.demo.comtroller;

import com.ls.spring.demo.service.IHelloServie;
import com.ls.spring.framework.annotation.MyAutowired;
import com.ls.spring.framework.annotation.MyController;
import com.ls.spring.framework.annotation.MyRequestParam;
import com.ls.spring.framework.annotation.MyResquestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@MyController
@MyResquestMapping("/hello")
public class HelloController {

    @MyAutowired
    private IHelloServie helloService;

    @MyResquestMapping("/sayHello")
    public void sayHello(HttpServletRequest request, HttpServletResponse response, @MyRequestParam("name") String name) {
        String result = "Hello!My name is " + name + "!";
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @MyResquestMapping("/add")
    public void add(HttpServletRequest request, HttpServletResponse response,
                    @MyRequestParam("a") Integer a, @MyRequestParam("b") Integer b) {
        int c = a + b;
        String result = "a + b = " + c;
        try {
            response.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
