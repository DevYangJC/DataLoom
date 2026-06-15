package com.demo.excel;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Excel 协作编辑系统 Demo - 启动类
 *
 * @author 杨纪聪
 * @date 2026-06-03
 */
@SpringBootApplication
@MapperScan("com.demo.excel.mapper")
public class ExcelServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExcelServiceApplication.class, args);
    }
}
