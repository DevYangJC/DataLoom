package com.demo.excel.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Excel Sheet 及数据分块查询服务
 * <p>
 * 职责：
 * <ul>
 *   <li>查询文档下的所有 Sheet 元信息</li>
 *   <li>按 Sheet ID 加载全部或指定范围的数据分块</li>
 *   <li>删除文档时级联清理 Sheet 和 Chunk 数据</li>
 * </ul>
 */
@Service
public class ExcelSheetService {

    @Autowired
    private ExcelSheetMapper sheetMapper;

    @Autowired
    private ExcelSheetChunkMapper chunkMapper;

    @Autowired
    private ExcelDocumentService documentService;

    /**
     * 查询文档下的所有 Sheet 元信息列表（按 sheetIndex 升序，不含 celldata）
     *
     * @param documentId 文档 ID
     * @return Sheet 元信息列表
     */
    public List<ExcelSheet> listSheetsByDocumentId(Long documentId) {
        QueryWrapper<ExcelSheet> qw = new QueryWrapper<>();
        qw.eq("document_id", documentId)
          .eq("status", 1)
          .orderByAsc("sheet_index");
        return sheetMapper.selectList(qw);
    }

    /**
     * 获取指定 Sheet 的所有数据分块（按 chunkIndex 升序）
     * <p>
     * 注意：返回的是完整的分块列表，调用方可按需合并 celldataJson 或按块分页加载。
     *
     * @param sheetId Sheet ID
     * @return 分块列表
     */
    public List<ExcelSheetChunk> listChunksBySheetId(Long sheetId) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId)
          .orderByAsc("chunk_index");
        return chunkMapper.selectList(qw);
    }

    /**
     * 分页获取指定 Sheet 的数据分块（按 chunkIndex 范围查询）
     * <p>
     * 前端可先获取 Sheet 元信息中的 chunkCount，再按需加载某几块，
     * 实现大数据量的虚拟滚动或懒加载。
     *
     * @param sheetId    Sheet ID
     * @param chunkStart 起始块序号（含）
     * @param chunkEnd   结束块序号（含）
     * @return 分块列表
     */
    public List<ExcelSheetChunk> listChunksByRange(Long sheetId, int chunkStart, int chunkEnd) {
        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId)
          .ge("chunk_index", chunkStart)
          .le("chunk_index", chunkEnd)
          .orderByAsc("chunk_index");
        return chunkMapper.selectList(qw);
    }

    /**
     * 删除文档下所有 Sheet 及 Chunk 数据（软删除 Sheet，物理删除 Chunk）
     *
     * @param documentId 文档 ID
     */
    public void deleteByDocumentId(Long documentId) {
        // 软删除 Sheet
        ExcelSheet update = new ExcelSheet();
        update.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(update, sheetQw);

        // 物理删除 Chunk（数据量大，不做逻辑删除）
        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);
    }

    /**
     * 实时增量更新单个单元格数据
     *
     * @param documentId 文档 ID
     * @param sheetId    Sheet ID
     * @param r          行号
     * @param c          列号
     * @param cellValue  单元格 v 对象（Luckysheet 格式）
     */
    public void updateCell(Long documentId, Long sheetId, int r, int c, com.alibaba.fastjson.JSONObject cellValue) {
        // 计算应该落在哪个分块（依据 ExcelParserService.CHUNK_SIZE=1000）
        int chunkSize = ExcelParserService.CHUNK_SIZE;
        int targetChunkIndex = r / chunkSize;

        QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
        qw.eq("sheet_id", sheetId).eq("chunk_index", targetChunkIndex);
        ExcelSheetChunk chunk = chunkMapper.selectOne(qw);

        com.alibaba.fastjson.JSONArray cellArray;
        boolean isNewChunk = false;

        if (chunk == null) {
            // 这是一块之前完全没有数据（全空白）的区域，新建 Chunk
            isNewChunk = true;
            chunk = new ExcelSheetChunk();
            chunk.setDocumentId(documentId);
            chunk.setSheetId(sheetId);
            chunk.setChunkIndex(targetChunkIndex);
            chunk.setRowStart(targetChunkIndex * chunkSize);
            chunk.setRowEnd((targetChunkIndex + 1) * chunkSize - 1);
            cellArray = new com.alibaba.fastjson.JSONArray();
        } else {
            // 解析现有的 celldata
            String jsonStr = chunk.getCelldataJson();
            cellArray = (jsonStr != null && !jsonStr.isEmpty())
                    ? com.alibaba.fastjson.JSONArray.parseArray(jsonStr)
                    : new com.alibaba.fastjson.JSONArray();
        }

        // 查找是否已存在该坐标的单元格
        boolean found = false;
        for (int i = 0; i < cellArray.size(); i++) {
            com.alibaba.fastjson.JSONObject cell = cellArray.getJSONObject(i);
            if (cell.getIntValue("r") == r && cell.getIntValue("c") == c) {
                // 如果前端传 null 或者空，代表清空单元格；否则覆盖更新
                if (cellValue == null || cellValue.isEmpty()) {
                    cellArray.remove(i);
                } else {
                    cell.put("v", cellValue);
                }
                found = true;
                break;
            }
        }

        // 不存在且不是清空操作，则追加
        if (!found && cellValue != null && !cellValue.isEmpty()) {
            com.alibaba.fastjson.JSONObject newCell = new com.alibaba.fastjson.JSONObject();
            newCell.put("r", r);
            newCell.put("c", c);
            newCell.put("v", cellValue);
            cellArray.add(newCell);
        }

        // 回写 JSON
        chunk.setCelldataJson(cellArray.toJSONString());

        if (isNewChunk) {
            chunkMapper.insert(chunk);
            // 更新 sheet 的 chunk_count（只增不减）
            ExcelSheet sheet = sheetMapper.selectById(sheetId);
            if (sheet != null && sheet.getChunkCount() <= targetChunkIndex) {
                ExcelSheet sheetUpdate = new ExcelSheet();
                sheetUpdate.setId(sheetId);
                sheetUpdate.setChunkCount(targetChunkIndex + 1);
                sheetMapper.updateById(sheetUpdate);
            }
        } else {
            chunkMapper.updateById(chunk);
        }
    }

    /**
     * 批量事务更新多个单元格
     *
     * @param documentId 文档 ID
     * @param updates    更新列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateCells(Long documentId, List<Map<String, Object>> updates) {
        // 为了性能优化，按 chunk 分组，减少数据库 I/O
        Map<String, List<Map<String, Object>>> chunkGroup = new HashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (Map<String, Object> update : updates) {
            Long sheetId = Long.parseLong(update.get("sheetId").toString());
            int r = Integer.parseInt(update.get("r").toString());
            int targetChunkIndex = r / chunkSize;
            String key = sheetId + "_" + targetChunkIndex;
            chunkGroup.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(update);
        }

        // 按 Chunk 批量处理
        for (Map.Entry<String, List<Map<String, Object>>> entry : chunkGroup.entrySet()) {
            String[] parts = entry.getKey().split("_");
            Long sheetId = Long.parseLong(parts[0]);
            int targetChunkIndex = Integer.parseInt(parts[1]);

            QueryWrapper<ExcelSheetChunk> qw = new QueryWrapper<>();
            qw.eq("sheet_id", sheetId).eq("chunk_index", targetChunkIndex);
            ExcelSheetChunk chunk = chunkMapper.selectOne(qw);

            com.alibaba.fastjson.JSONArray cellArray;
            boolean isNewChunk = false;

            if (chunk == null) {
                isNewChunk = true;
                chunk = new ExcelSheetChunk();
                chunk.setDocumentId(documentId);
                chunk.setSheetId(sheetId);
                chunk.setChunkIndex(targetChunkIndex);
                chunk.setRowStart(targetChunkIndex * chunkSize);
                chunk.setRowEnd((targetChunkIndex + 1) * chunkSize - 1);
                cellArray = new com.alibaba.fastjson.JSONArray();
            } else {
                String jsonStr = chunk.getCelldataJson();
                cellArray = (jsonStr != null && !jsonStr.isEmpty())
                        ? com.alibaba.fastjson.JSONArray.parseArray(jsonStr)
                        : new com.alibaba.fastjson.JSONArray();
            }

            for (Map<String, Object> update : entry.getValue()) {
                int r = Integer.parseInt(update.get("r").toString());
                int c = Integer.parseInt(update.get("c").toString());
                Object vObj = update.get("v");
                com.alibaba.fastjson.JSONObject cellValue = null;
                if (vObj != null) {
                    cellValue = com.alibaba.fastjson.JSONObject.parseObject(com.alibaba.fastjson.JSONObject.toJSONString(vObj));
                }

                boolean found = false;
                for (int i = 0; i < cellArray.size(); i++) {
                    com.alibaba.fastjson.JSONObject cell = cellArray.getJSONObject(i);
                    if (cell.getIntValue("r") == r && cell.getIntValue("c") == c) {
                        if (cellValue == null || cellValue.isEmpty()) {
                            cellArray.remove(i);
                        } else {
                            cell.put("v", cellValue);
                        }
                        found = true;
                        break;
                    }
                }

                if (!found && cellValue != null && !cellValue.isEmpty()) {
                    com.alibaba.fastjson.JSONObject newCell = new com.alibaba.fastjson.JSONObject();
                    newCell.put("r", r);
                    newCell.put("c", c);
                    newCell.put("v", cellValue);
                    cellArray.add(newCell);
                }
            }

            chunk.setCelldataJson(cellArray.toJSONString());

            if (isNewChunk) {
                chunkMapper.insert(chunk);
                ExcelSheet sheet = sheetMapper.selectById(sheetId);
                if (sheet != null && sheet.getChunkCount() <= targetChunkIndex) {
                    ExcelSheet sheetUpdate = new ExcelSheet();
                    sheetUpdate.setId(sheetId);
                    sheetUpdate.setChunkCount(targetChunkIndex + 1);
                    sheetMapper.updateById(sheetUpdate);
                }
            } else {
                chunkMapper.updateById(chunk);
            }
        }
    }

    /**
     * Replace all persisted sheets for a document with the current Luckysheet workbook snapshot.
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceWorkbook(Long documentId, List<Map<String, Object>> workbookSheets) {
        if (workbookSheets == null || workbookSheets.isEmpty()) {
            throw new IllegalArgumentException("workbook sheets must not be empty");
        }

        QueryWrapper<ExcelSheetChunk> chunkQw = new QueryWrapper<>();
        chunkQw.eq("document_id", documentId);
        chunkMapper.delete(chunkQw);

        ExcelSheet deleted = new ExcelSheet();
        deleted.setStatus(3);
        QueryWrapper<ExcelSheet> sheetQw = new QueryWrapper<>();
        sheetQw.eq("document_id", documentId);
        sheetMapper.update(deleted, sheetQw);

        List<String> sheetNames = new ArrayList<>();
        int visibleIndex = 0;

        for (Map<String, Object> rawSheet : workbookSheets) {
            if (rawSheet == null) continue;

            String name = stringValue(rawSheet.get("name"), "Sheet" + (visibleIndex + 1));
            sheetNames.add(name);

            JSONObject config = toJsonObject(rawSheet.get("config"));
            JSONObject hyperlink = toJsonObject(rawSheet.get("hyperlink"));
            if (hyperlink == null) {
                hyperlink = new JSONObject();
            }
            JSONObject images = toJsonObject(rawSheet.get("images"));
            if (images == null) {
                images = new JSONObject();
            }
            JSONArray conditionFormat = toJsonArray(rawSheet.get("luckysheet_conditionformat_save"));
            if (conditionFormat == null) {
                conditionFormat = new JSONArray();
            }
            JSONArray chart = toJsonArray(rawSheet.get("chart"));
            if (chart == null) {
                chart = new JSONArray();
            }

            JSONObject merge = config.getJSONObject("merge");
            JSONObject columnLen = config.getJSONObject("columnlen");
            JSONObject rowLen = config.getJSONObject("rowlen");

            if (merge == null) merge = new JSONObject();
            if (columnLen == null) columnLen = new JSONObject();
            if (rowLen == null) rowLen = new JSONObject();
            config.put("merge", merge);
            config.put("columnlen", columnLen);
            config.put("rowlen", rowLen);

            JSONArray celldata = resolveCelldata(rawSheet);
            int totalRows = intValue(rawSheet.get("row"), calcTotalRows(rawSheet.get("data"), celldata));
            int totalCols = intValue(rawSheet.get("column"), calcTotalCols(rawSheet.get("data"), celldata));

            ExcelSheet sheet = new ExcelSheet();
            sheet.setDocumentId(documentId);
            sheet.setSheetIndex(visibleIndex);
            sheet.setSheetName(name);
            sheet.setTotalRows(Math.max(totalRows, 1));
            sheet.setTotalCols(Math.max(totalCols, 1));
            sheet.setChunkCount(0);
            sheet.setMergeConfigJson(merge.toJSONString());
            sheet.setColumnLenJson(columnLen.toJSONString());
            sheet.setRowLenJson(rowLen.toJSONString());
            sheet.setConfigJson(config.toJSONString());
            sheet.setHyperlinkConfigJson(hyperlink.toJSONString());
            sheet.setImagesConfigJson(images.toJSONString());
            sheet.setConditionFormatJson(conditionFormat.toJSONString());
            sheet.setChartJson(chart.toJSONString());
            sheet.setActive(intValue(rawSheet.get("status"), visibleIndex == 0 ? 1 : 0));
            sheet.setStatus(1);
            sheetMapper.insert(sheet);

            int chunkCount = saveCelldataChunks(documentId, sheet.getId(), celldata, sheet.getTotalRows());
            ExcelSheet update = new ExcelSheet();
            update.setId(sheet.getId());
            update.setChunkCount(chunkCount);
            sheetMapper.updateById(update);

            visibleIndex++;
        }

        if (sheetNames.isEmpty()) {
            throw new IllegalArgumentException("workbook contains no valid sheets");
        }

        documentService.updateSheetMeta(documentId, sheetNames.size(), JSONArray.toJSONString(sheetNames));
    }

    private int saveCelldataChunks(Long documentId, Long sheetId, JSONArray celldata, int totalRows) {
        Map<Integer, JSONArray> chunks = new LinkedHashMap<>();
        int chunkSize = ExcelParserService.CHUNK_SIZE;

        for (int i = 0; i < celldata.size(); i++) {
            JSONObject cell = celldata.getJSONObject(i);
            int row = cell.getIntValue("r");
            int chunkIndex = row / chunkSize;
            chunks.computeIfAbsent(chunkIndex, key -> new JSONArray()).add(cell);
        }

        if (chunks.isEmpty()) {
            return Math.max(1, (int) Math.ceil(Math.max(totalRows, 1) / (double) chunkSize));
        }

        for (Map.Entry<Integer, JSONArray> entry : chunks.entrySet()) {
            int chunkIndex = entry.getKey();
            ExcelSheetChunk chunk = new ExcelSheetChunk();
            chunk.setDocumentId(documentId);
            chunk.setSheetId(sheetId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setRowStart(chunkIndex * chunkSize);
            chunk.setRowEnd((chunkIndex + 1) * chunkSize - 1);
            chunk.setCelldataJson(entry.getValue().toJSONString());
            chunkMapper.insert(chunk);
        }

        return chunks.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
    }

    private JSONArray resolveCelldata(Map<String, Object> rawSheet) {
        Object celldataObj = rawSheet.get("celldata");
        JSONArray celldata = toJsonArray(celldataObj);
        if (!celldata.isEmpty()) return normalizeCelldata(celldata);

        return dataMatrixToCelldata(rawSheet.get("data"));
    }

    private JSONArray normalizeCelldata(JSONArray celldata) {
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < celldata.size(); i++) {
            JSONObject cell = celldata.getJSONObject(i);
            if (cell == null || !cell.containsKey("r") || !cell.containsKey("c")) continue;
            Object value = cell.get("v");
            if (isEmptyCellValue(value)) continue;
            normalized.add(cell);
        }
        return normalized;
    }

    private JSONArray dataMatrixToCelldata(Object dataObj) {
        JSONArray rows = toJsonArray(dataObj);
        JSONArray celldata = new JSONArray();

        for (int r = 0; r < rows.size(); r++) {
            JSONArray row = toJsonArray(rows.get(r));
            for (int c = 0; c < row.size(); c++) {
                Object value = row.get(c);
                if (isEmptyCellValue(value)) continue;

                JSONObject cell = new JSONObject();
                cell.put("r", r);
                cell.put("c", c);
                cell.put("v", toJsonObject(value));
                celldata.add(cell);
            }
        }
        return celldata;
    }

    private boolean isEmptyCellValue(Object value) {
        if (value == null) return true;
        if (value instanceof JSONObject) return ((JSONObject) value).isEmpty();
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        return false;
    }

    private int calcTotalRows(Object dataObj, JSONArray celldata) {
        JSONArray rows = toJsonArray(dataObj);
        int max = rows.size();
        for (int i = 0; i < celldata.size(); i++) {
            max = Math.max(max, celldata.getJSONObject(i).getIntValue("r") + 1);
        }
        return max;
    }

    private int calcTotalCols(Object dataObj, JSONArray celldata) {
        JSONArray rows = toJsonArray(dataObj);
        int max = 0;
        for (int r = 0; r < rows.size(); r++) {
            max = Math.max(max, toJsonArray(rows.get(r)).size());
        }
        for (int i = 0; i < celldata.size(); i++) {
            max = Math.max(max, celldata.getJSONObject(i).getIntValue("c") + 1);
        }
        return max;
    }

    private JSONObject toJsonObject(Object value) {
        if (value == null) return new JSONObject();
        if (value instanceof JSONObject) return (JSONObject) value;
        return JSONObject.parseObject(JSONObject.toJSONString(value));
    }

    private JSONArray toJsonArray(Object value) {
        if (value == null) return new JSONArray();
        if (value instanceof JSONArray) return (JSONArray) value;
        return JSONArray.parseArray(JSONArray.toJSONString(value));
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String stringValue(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String text = value.toString();
        return text.trim().isEmpty() ? defaultValue : text;
    }
}
