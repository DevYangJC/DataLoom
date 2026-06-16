package com.demo.excel.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.demo.excel.common.ApiResponse;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.service.ExcelDocumentService;
import com.demo.excel.service.ExcelParserService;
import com.demo.excel.service.ExcelSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Excel 文件上传接口
 * <p>
 * 注意：导出功能已由前端 ExcelJS 接管（保证样式零丢失），后端不提供导出接口。
 */
@RestController
@RequestMapping("/api/excel")
public class ExcelFileController {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileController.class);

    @Autowired
    private ExcelParserService parserService;

    @Autowired
    private ExcelDocumentService documentService;

    @Autowired
    private ExcelSheetService sheetService;

    @Value("${excel.upload.path:./upload}")
    private String uploadPath;

    // =========================================================
    // 上传接口
    // =========================================================

    /**
     * 上传 Excel 文件（支持十万级数据，多 Sheet）
     * <p>
     * 处理流程：
     * <ol>
     *   <li>保存原始文件到本地磁盘（用于备份 / 导出）</li>
     *   <li>流式 POI 解析，按 {@code 1000行/块} 分块写入数据库</li>
     *   <li>返回文档 ID 和 Sheet 元信息列表（不含 celldata，避免响应体过大）</li>
     * </ol>
     *
     * @param file 上传的 Excel 文件（.xlsx / .xls）
     * @return 文档 ID、Sheet 元信息列表
     */
    @PostMapping("/upload")
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            // 1. 保存原始文件
            String originalName = file.getOriginalFilename();
            String savedName = UUID.randomUUID().toString() + "_" + originalName;
            Path dir = Paths.get(uploadPath).toAbsolutePath().normalize();
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path filePath = dir.resolve(savedName);
            file.transferTo(filePath.toFile());

            // 2. 先插入文档主记录（获取 documentId 供分块写入时使用）
            ExcelDocument doc = new ExcelDocument();
            doc.setName(originalName);
            doc.setFilePath(filePath.toString());
            doc.setFileSize(file.getSize());
            doc.setCreatorId("demo-user");
            documentService.create(doc);

            // 3. 流式解析 + 分块写库（核心改造点）
            List<ExcelSheet> sheets = parserService.parseAndSave(
                    new FileInputStream(filePath.toFile()), doc);

            // 4. 更新文档的 sheetCount / sheetNames
            List<String> sheetNames = new ArrayList<>();
            for (ExcelSheet s : sheets) {
                sheetNames.add(s.getSheetName());
            }
            documentService.updateSheetMeta(doc.getId(), sheets.size(),
                    JSONArray.toJSONString(sheetNames));

            // 5. 组装响应（只返回元信息，不返回 celldata）
            List<Map<String, Object>> sheetInfoList = buildSheetInfoList(sheets);
            Map<String, Object> result = new HashMap<>();
            result.put("documentId", doc.getId());
            result.put("name", doc.getName());
            result.put("sheetCount", sheets.size());
            result.put("sheets", sheetInfoList);   // 元信息，前端用于展示 Sheet 列表

            log.info("上传成功: {} → documentId={}, sheets={}", originalName, doc.getId(), sheets.size());
            return ApiResponse.ok("上传成功", result);

        } catch (Exception e) {
            log.error("上传失败", e);
            return ApiResponse.fail("上传失败: " + e.getMessage());
        }
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 将 ExcelSheet 列表转为前端元信息 Map 列表（不含 celldata）
     */
    private List<Map<String, Object>> buildSheetInfoList(List<ExcelSheet> sheets) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExcelSheet s : sheets) {
            Map<String, Object> info = new HashMap<>();
            info.put("sheetId", s.getId());
            info.put("sheetIndex", s.getSheetIndex());
            info.put("sheetName", s.getSheetName());
            info.put("totalRows", s.getTotalRows());
            info.put("totalCols", s.getTotalCols());
            info.put("chunkCount", s.getChunkCount());
            info.put("active", s.getActive());
            list.add(info);
        }
        return list;
    }
}
