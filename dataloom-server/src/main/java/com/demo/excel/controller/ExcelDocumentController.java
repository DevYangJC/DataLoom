package com.demo.excel.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.demo.excel.common.ApiResponse;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.service.ExcelDocumentService;
import com.demo.excel.service.ExcelSheetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Excel 文档 CRUD + 分块数据加载接口
 * <p>
 * 接口设计说明（针对十万级数据）：
 * <ul>
 *   <li>{@code GET /list} — 文档列表，不含 celldata</li>
 *   <li>{@code GET /{id}} — 文档元信息 + 各 Sheet 元信息，不含 celldata</li>
 *   <li>{@code GET /{id}/sheet/{sheetId}/chunks} — 按块序号范围加载 celldata，支持懒加载</li>
 *   <li>{@code GET /{id}/sheet/{sheetId}/all} — 加载指定 Sheet 全量数据（谨慎使用）</li>
 *   <li>{@code DELETE /{id}} — 软删除文档，同步清理 Sheet 和 Chunk</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/excel/document")
public class ExcelDocumentController {

    private static final Logger log = LoggerFactory.getLogger(ExcelDocumentController.class);

    @Autowired
    private ExcelDocumentService documentService;

    @Autowired
    private ExcelSheetService sheetService;

    // =========================================================
    // 文档基础接口
    // =========================================================

    /**
     * 文档列表（分页）— 只返回元数据，不含任何单元格数据
     *
     * @param pageNum  页码（默认 1）
     * @param pageSize 每页条数（默认 20）
     * @return 分页文档列表
     */
    @GetMapping("/list")
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int pageNum,
                               @RequestParam(defaultValue = "20") int pageSize) {
        Page<ExcelDocument> page = documentService.listByPage(pageNum, pageSize);

        List<Map<String, Object>> records = new ArrayList<>();
        for (ExcelDocument doc : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("name", doc.getName());
            item.put("sheetCount", doc.getSheetCount());
            item.put("sheetNames", doc.getSheetNames());
            item.put("version", doc.getVersion());
            item.put("fileSize", doc.getFileSize());
            item.put("createTime", doc.getCreateTime());
            item.put("updateTime", doc.getUpdateTime());
            records.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("current", page.getCurrent());
        result.put("records", records);
        return ApiResponse.ok(result);
    }

    /**
     * 文档元信息详情（含各 Sheet 元信息，但不含 celldata）
     * <p>
     * 前端打开文档时先调用此接口，获取 Sheet 列表和 chunkCount，
     * 再按需调用 chunks 接口懒加载数据。
     *
     * @param id 文档 ID
     * @return 文档 + Sheet 元信息
     */
    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        ExcelDocument doc = documentService.getById(id);
        if (doc == null) {
            return ApiResponse.fail(404, "文档不存在");
        }

        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(id);

        // 组装 Sheet 元信息（包含 config，不含 celldata）
        List<Map<String, Object>> sheetInfoList = new ArrayList<>();
        for (ExcelSheet s : sheets) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("sheetId", s.getId());
            info.put("sheetIndex", s.getSheetIndex());
            info.put("sheetName", s.getSheetName());
            info.put("totalRows", s.getTotalRows());
            info.put("totalCols", s.getTotalCols());
            info.put("chunkCount", s.getChunkCount());
            info.put("active", s.getActive());
            // 合并单元格和列宽解析后放入（结构较小）
            JSONObject mergeConfig = parseObjectOrEmpty(s.getMergeConfigJson());
            JSONObject columnLen = parseObjectOrEmpty(s.getColumnLenJson());
            JSONObject rowLen = parseObjectOrEmpty(s.getRowLenJson());
            JSONObject config = parseObjectOrEmpty(s.getConfigJson());
            if (config.isEmpty()) {
                config.put("merge", mergeConfig);
                config.put("columnlen", columnLen);
                config.put("rowlen", rowLen);
            }
            info.put("config", config);
            info.put("mergeConfig", mergeConfig);
            info.put("columnLen", columnLen);
            info.put("rowLen", rowLen);
            JSONObject hyperlink = parseObjectOrEmpty(s.getHyperlinkConfigJson());
            info.put("hyperlink", hyperlink);
            JSONObject images = parseObjectOrEmpty(s.getImagesConfigJson());
            info.put("images", images);
            JSONArray conditionFormat = parseArrayOrEmpty(s.getConditionFormatJson());
            info.put("luckysheet_conditionformat_save", conditionFormat);
            JSONArray chart = parseArrayOrEmpty(s.getChartJson());
            info.put("chart", chart);
            sheetInfoList.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());
        result.put("name", doc.getName());
        result.put("sheetCount", doc.getSheetCount());
        result.put("version", doc.getVersion());
        result.put("sheets", sheetInfoList);
        return ApiResponse.ok(result);
    }

    // =========================================================
    // 分块数据加载接口（核心新增）
    // =========================================================

    /**
     * 按块序号范围加载指定 Sheet 的 celldata（懒加载 / 虚拟滚动场景）
     * <p>
     * 示例：chunkStart=0 & chunkEnd=2 加载前3块（约3000行）的数据
     *
     * @param id         文档 ID
     * @param sheetId    Sheet ID（来自 detail 接口）
     * @param chunkStart 起始块序号（默认 0）
     * @param chunkEnd   结束块序号（默认 0，即只加载第一块）
     * @return 指定范围内所有分块的 celldata，已合并为一个 celldata 数组
     */
    @GetMapping("/{id}/sheet/{sheetId}/chunks")
    public ApiResponse<?> loadChunks(@PathVariable Long id,
                                     @PathVariable Long sheetId,
                                     @RequestParam(defaultValue = "0") int chunkStart,
                                     @RequestParam(defaultValue = "0") int chunkEnd) {
        List<ExcelSheetChunk> chunks = sheetService.listChunksByRange(sheetId, chunkStart, chunkEnd);

        // 合并所有块的 celldata 为一个大数组返回给前端
        JSONArray mergedCelldata = new JSONArray();
        int rowStart = Integer.MAX_VALUE;
        int rowEnd = 0;

        for (ExcelSheetChunk chunk : chunks) {
            try {
                JSONArray cells = JSONArray.parseArray(chunk.getCelldataJson());
                if (cells != null) {
                    mergedCelldata.addAll(cells);
                }
                if (chunk.getRowStart() < rowStart) rowStart = chunk.getRowStart();
                if (chunk.getRowEnd() > rowEnd)     rowEnd   = chunk.getRowEnd();
            } catch (Exception e) {
                log.warn("解析 chunk[{}] 失败: {}", chunk.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetId", sheetId);
        result.put("chunkStart", chunkStart);
        result.put("chunkEnd", chunkEnd);
        result.put("rowStart", chunks.isEmpty() ? 0 : rowStart);
        result.put("rowEnd", chunks.isEmpty() ? 0 : rowEnd);
        result.put("celldata", mergedCelldata);
        result.put("cellCount", mergedCelldata.size());
        return ApiResponse.ok(result);
    }

    /**
     * 加载指定 Sheet 的全量 celldata（适合小数据量 Sheet，大数据量请用 chunks 接口）
     * <p>
     * 警告：若 Sheet 数据量超过 10 万行，建议改用 chunks 接口分块加载。
     *
     * @param id      文档 ID
     * @param sheetId Sheet ID
     * @return 全量 celldata 数组（Luckysheet 格式）
     */
    @GetMapping("/{id}/sheet/{sheetId}/all")
    public ApiResponse<?> loadAllCelldata(@PathVariable Long id,
                                          @PathVariable Long sheetId) {
        List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheetId);

        JSONArray mergedCelldata = new JSONArray();
        for (ExcelSheetChunk chunk : chunks) {
            try {
                JSONArray cells = JSONArray.parseArray(chunk.getCelldataJson());
                if (cells != null) mergedCelldata.addAll(cells);
            } catch (Exception e) {
                log.warn("解析 chunk[{}] 失败: {}", chunk.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sheetId", sheetId);
        result.put("celldata", mergedCelldata);
        result.put("cellCount", mergedCelldata.size());
        return ApiResponse.ok(result);
    }

    // =========================================================
    // 文档管理接口
    // =========================================================

    /**
     * 更新文档名称
     *
     * @param id   文档 ID
     * @param body 请求体，包含 name 字段
     * @return 操作结果
     */
    @PutMapping("/{id}/name")
    public ApiResponse<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        if (newName == null || newName.trim().isEmpty()) {
            return ApiResponse.fail("名称不能为空");
        }
        ExcelDocument doc = documentService.getById(id);
        if (doc == null) {
            return ApiResponse.fail(404, "文档不存在");
        }
        documentService.rename(id, newName.trim());
        return ApiResponse.ok("重命名成功");
    }

    /**
     * 增量更新单个单元格（改一格存一格）
     *
     * @param id      文档 ID
     * @param sheetId Sheet ID
     * @param body    请求体，包含 r, c, v (v为luckysheet的单元格值对象)
     * @return 操作结果
     */
    @PutMapping("/{id}/sheet/{sheetId}/cell")
    public ApiResponse<?> updateCell(@PathVariable Long id,
                                     @PathVariable Long sheetId,
                                     @RequestBody Map<String, Object> body) {
        try {
            int r = Integer.parseInt(body.get("r").toString());
            int c = Integer.parseInt(body.get("c").toString());
            Object vObj = body.get("v");
            
            // v 可能为 null（比如用户按 Delete 键清空单元格）
            JSONObject v = null;
            if (vObj != null) {
                // 将接收到的 Map 转换为 JSONObject
                v = JSONObject.parseObject(JSONObject.toJSONString(vObj));
            }

            sheetService.updateCell(id, sheetId, r, c, v);
            return ApiResponse.ok("更新成功");
        } catch (Exception e) {
            log.error("更新单元格失败, doc={}, sheet={}, error={}", id, sheetId, e.getMessage());
            return ApiResponse.fail("更新失败: " + e.getMessage());
        }
    }

    /**
     * 批量更新多个单元格（用于手动保存按钮）
     *
     * @param id      文档 ID
     * @param updates 单元格修改列表：[{sheetId: 1, r: 0, c: 0, v: {...}}, ...]
     * @return 操作结果
     */
    @PutMapping("/{id}/cells/batch")
    public ApiResponse<?> batchUpdateCells(@PathVariable Long id,
                                           @RequestBody List<Map<String, Object>> updates) {
        try {
            if (updates == null || updates.isEmpty()) {
                return ApiResponse.ok("没有需要保存的修改");
            }
            
            // 调用 Service 层的事务批量更新，大幅减少数据库连接和读写次数
            sheetService.batchUpdateCells(id, updates);
            
            log.info("批量更新单元格成功, doc={}, 数量={}", id, updates.size());
            return ApiResponse.ok("保存成功");
        } catch (Exception e) {
            log.error("批量更新单元格失败, doc={}, error={}", id, e.getMessage());
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    /**
     * Save the full Luckysheet workbook snapshot.
     * This keeps sheet creation, rename, ordering, row/column settings, merges and celldata together.
     */
    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/workbook")
    public ApiResponse<?> saveWorkbook(@PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        try {
            Object sheetsObj = body.get("sheets");
            if (!(sheetsObj instanceof List)) {
                return ApiResponse.fail("sheets 不能为空");
            }

            List<Map<String, Object>> sheets = (List<Map<String, Object>>) sheetsObj;
            sheetService.replaceWorkbook(id, sheets);

            log.info("工作簿快照保存成功: documentId={}, sheetCount={}", id, sheets.size());
            return ApiResponse.ok("保存成功");
        } catch (Exception e) {
            log.error("工作簿快照保存失败: documentId={}, error={}", id, e.getMessage(), e);
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        documentService.delete(id);
        sheetService.deleteByDocumentId(id);
        log.info("文档已删除: documentId={}", id);
        return ApiResponse.ok("删除成功");
    }

    private JSONObject parseObjectOrEmpty(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSONObject.parseObject(json);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray parseArrayOrEmpty(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new JSONArray();
        }
        try {
            JSONArray parsed = JSONArray.parseArray(json);
            return parsed == null ? new JSONArray() : parsed;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }
}
