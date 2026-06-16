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
 * Excel 文档接口 — 文档 CRUD + 工作簿保存 + 单元格批量更新
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
    // ① 查询接口
    // =========================================================

    /**
     * 文档列表（分页，仅元数据，不含单元格数据）
     *
     * @param pageNum  页码，默认 1
     * @param pageSize 每页条数，默认 20
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
     * 文档详情 — 含各 Sheet 元信息（config / chart / images / hyperlink 等），不含 celldata
     * <p>
     * 前端打开文档时先调此接口获取 Sheet 列表和 chunkCount，
     * 再调 all 接口加载全量 celldata。
     */
    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        ExcelDocument doc = documentService.getById(id);
        if (doc == null) {
            return ApiResponse.fail(404, "文档不存在");
        }

        List<ExcelSheet> sheets = sheetService.listSheetsByDocumentId(id);

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

            // 组装 config（含 merge / columnlen / rowlen）
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

            // 超链接配置
            info.put("hyperlink", parseObjectOrEmpty(s.getHyperlinkConfigJson()));
            // 图片配置
            info.put("images", parseObjectOrEmpty(s.getImagesConfigJson()));
            // 条件格式配置
            info.put("luckysheet_conditionformat_save", parseArrayOrEmpty(s.getConditionFormatJson()));
            // 图表配置
            info.put("chart", parseArrayOrEmpty(s.getChartJson()));

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

    /**
     * 加载指定 Sheet 的全部 celldata（合并所有分块返回）
     * <p>
     * 适用场景：前端打开文档后一次性拉取整个 Sheet 的单元格数据。
     * 对于超大 Sheet（10 万行以上），建议评估性能后使用。
     */
    @GetMapping("/{id}/sheet/{sheetId}/all")
    public ApiResponse<?> loadAllCelldata(@PathVariable Long id,
                                          @PathVariable Long sheetId) {
        List<ExcelSheetChunk> chunks = sheetService.listChunksBySheetId(sheetId);

        JSONArray mergedCelldata = new JSONArray();
        for (ExcelSheetChunk chunk : chunks) {
            try {
                JSONArray cells = JSONArray.parseArray(chunk.getCelldataJson());
                if (cells != null) {
                    mergedCelldata.addAll(cells);
                }
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
    // ② 写入接口
    // =========================================================

    /**
     * 批量增量更新单元格 — 按 Chunk 分组后逐块回写，只影响相关分块
     * <p>
     * 前端保存时，如果只有单元格内容变化（无结构 / 图片 / 图表变更），
     * 优先走此接口，避免全量重建所有分块。
     *
     * @param id      文档 ID
     * @param updates 单元格修改列表：[{"sheetId": 1, "r": 0, "c": 1, "v": {...}}, ...]
     */
    @PutMapping("/{id}/cells/batch")
    public ApiResponse<?> batchUpdateCells(@PathVariable Long id,
                                           @RequestBody List<Map<String, Object>> updates) {
        try {
            if (updates == null || updates.isEmpty()) {
                return ApiResponse.ok("没有需要保存的修改");
            }
            sheetService.batchUpdateCells(id, updates);
            log.info("批量更新单元格成功: doc={}, count={}", id, updates.size());
            return ApiResponse.ok("保存成功");
        } catch (Exception e) {
            log.error("批量更新单元格失败: doc={}, error={}", id, e.getMessage());
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    /**
     * 全量快照保存 — 替换文档下所有 Sheet 和 Chunk（事务保护）
     * <p>
     * 适用场景：Sheet 结构变化（增删 Sheet / 改名 / 合并单元格 / 列宽 / 行高）、
     * 图片/图表/超链接/条件格式变更时使用。
     * 接口返回新的 sheetId 映射表，供前端后续增量保存使用。
     *
     * @param id   文档 ID
     * @param body {"sheets": [{...luckysheet 格式的 sheet 快照...}]}
     * @return {"sheetCount": N, "sheetIdMap": {"0": 新sheetId, "1": 新sheetId}}
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
            Map<Integer, Long> sheetIdMapping = sheetService.replaceWorkbook(id, sheets);

            log.info("工作簿全量保存成功: doc={}, sheetCount={}", id, sheets.size());

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("sheetCount", sheets.size());
            resultData.put("sheetIdMap", sheetIdMapping);
            return ApiResponse.ok("保存成功", resultData);
        } catch (Exception e) {
            log.error("工作簿全量保存失败: doc={}, error={}", id, e.getMessage(), e);
            return ApiResponse.fail("保存失败: " + e.getMessage());
        }
    }

    // =========================================================
    // ③ 文档管理接口
    // =========================================================

    /**
     * 重命名文档
     *
     * @param id   文档 ID
     * @param body {"name": "新名称"}
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
        log.info("文档重命名成功: doc={}, name={}", id, newName.trim());
        return ApiResponse.ok("重命名成功");
    }

    /**
     * 删除文档 — 软删除文档主记录 + 软删除 Sheet + 物理删除 Chunk
     *
     * @param id 文档 ID
     */
    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable Long id) {
        documentService.delete(id);
        sheetService.deleteByDocumentId(id);
        log.info("文档已删除: doc={}", id);
        return ApiResponse.ok("删除成功");
    }

    // =========================================================
    // ④ 私有工具方法
    // =========================================================

    /** 安全解析 JSON 对象，null / 空串 / 解析异常 → 返回空 JSONObject */
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

    /** 安全解析 JSON 数组，null / 空串 / 解析异常 → 返回空 JSONArray */
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
