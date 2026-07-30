package vip.xiaozhao.intern.baseUtil;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("vip.xiaozhao.intern.baseUtil.intf.mapper")
@EnableCaching
@EnableScheduling
public class NaofferWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(NaofferWebApplication.class, args);
    }

}
