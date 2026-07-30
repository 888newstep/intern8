package vip.xiaozhao.intern.baseUtil.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/")
@Slf4j
public class HomeController {

    @RequestMapping("/hello")
    public String init(HttpServletRequest request) {
        log.info("show hello");
        return "hello";
    }
}
