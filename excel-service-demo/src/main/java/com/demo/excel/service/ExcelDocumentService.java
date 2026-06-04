package com.demo.excel.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.mapper.ExcelDocumentMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Excel 文档主表 CRUD 服务
 * <p>
 * 注意：文档表仅存元数据（名称、sheet数量等），
 * 具体的单元格数据由 ExcelSheetService + ExcelSheetChunk 管理。
 */
@Service
public class ExcelDocumentService {

    @Autowired
    private ExcelDocumentMapper documentMapper;

    /**
     * 创建文档记录（初始化时不含 sheetCount/sheetNames，解析完成后调用 updateSheetMeta 更新）
     *
     * @param doc 文档实体（name、filePath、fileSize、creatorId 已填）
     * @return 填充了 ID 的文档实体
     */
    public ExcelDocument create(ExcelDocument doc) {
        doc.setStatus(1);
        doc.setVersion(1L);
        doc.setSheetCount(0);
        documentMapper.insert(doc);
        return doc;
    }

    /**
     * 解析完成后更新文档的 Sheet 数量和名称列表
     *
     * @param docId      文档 ID
     * @param sheetCount Sheet 数量
     * @param sheetNames Sheet 名称列表 JSON 字符串（如 ["Sheet1","Sheet2"]）
     */
    public void updateSheetMeta(Long docId, int sheetCount, String sheetNames) {
        ExcelDocument doc = new ExcelDocument();
        doc.setId(docId);
        doc.setSheetCount(sheetCount);
        doc.setSheetNames(sheetNames);
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    /**
     * 根据 ID 查询文档（含元数据，不含 celldata）
     *
     * @param id 文档 ID
     * @return 文档实体，不存在则返回 null
     */
    public ExcelDocument getById(Long id) {
        return documentMapper.selectById(id);
    }

    /**
     * 分页查询文档列表（只返回正常状态的文档，按更新时间降序）
     *
     * @param pageNum  页码（1起始）
     * @param pageSize 每页条数
     * @return 分页结果
     */
    public Page<ExcelDocument> listByPage(int pageNum, int pageSize) {
        Page<ExcelDocument> page = new Page<>(pageNum, pageSize);
        QueryWrapper<ExcelDocument> qw = new QueryWrapper<>();
        qw.eq("status", 1);
        qw.orderByDesc("update_time");
        return documentMapper.selectPage(page, qw);
    }

    /**
     * 软删除文档（status 改为 3，Sheet 和 Chunk 由 ExcelSheetService 单独清理）
     * 同时删除磁盘上的原始物理文件，防止磁盘被撑爆
     *
     * @param id 文档 ID
     */
    public void delete(Long id) {
        // 查询出文档实体，以便获取 filePath
        ExcelDocument existDoc = documentMapper.selectById(id);
        if (existDoc != null && existDoc.getFilePath() != null) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(existDoc.getFilePath());
                java.nio.file.Files.deleteIfExists(path);
            } catch (Exception e) {
                // 如果文件已被删除或占用，忽略报错，不影响数据库数据的清理
            }
        }

        // 软删除数据库记录
        ExcelDocument doc = new ExcelDocument();
        doc.setId(id);
        doc.setStatus(3);
        documentMapper.updateById(doc);
    }

    /**
     * 更新文档名称
     *
     * @param id      文档 ID
     * @param newName 新名称
     */
    public void rename(Long id, String newName) {
        ExcelDocument doc = new ExcelDocument();
        doc.setId(id);
        doc.setName(newName);
        doc.setUpdateTime(LocalDateTime.now());
        documentMapper.updateById(doc);
    }
}
