package com.demo.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.excel.entity.ExcelSheet;

/**
 * Excel Sheet Mapper（MyBatis-Plus）
 * <p>
 * 业务查询统一通过 QueryWrapper 在 Service 层组装，Mapper 层只继承基础 CRUD。
 */
public interface ExcelSheetMapper extends BaseMapper<ExcelSheet> {
}
