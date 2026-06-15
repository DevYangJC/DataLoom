package com.demo.excel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.excel.entity.ExcelSheetChunk;

/**
 * Excel Sheet 数据分块 Mapper（MyBatis-Plus）
 * <p>
 * 业务查询统一通过 QueryWrapper 在 Service 层组装，Mapper 层只继承基础 CRUD。
 */
public interface ExcelSheetChunkMapper extends BaseMapper<ExcelSheetChunk> {
}
