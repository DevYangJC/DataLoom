package com.demo.excel.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.demo.excel.entity.ExcelDocument;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 解析引擎 — 流式 POI 解析 + 分块持久化
 * <p>
 * 核心设计：
 * <ul>
 *   <li>使用 Apache POI {@link WorkbookFactory} 逐行读取，避免整体加载入内存</li>
 *   <li>按 {@code CHUNK_SIZE} 行为单位，将 celldata JSON 分块写入 excel_sheet_chunk 表</li>
 *   <li>合并单元格、列宽等小型配置存入 excel_sheet 表，不参与分块</li>
 *   <li>上层调用方无需关心分块细节，只传入 documentId 即可</li>
 * </ul>
 *
 * @author demo
 */
@Service
public class ExcelParserService {

    private static final Logger log = LoggerFactory.getLogger(ExcelParserService.class);

    /** 每块存储的最大行数（可按实际数据密度调整） */
    public static final int CHUNK_SIZE = 1000;

    @Autowired
    private ExcelSheetMapper sheetMapper;

    @Autowired
    private ExcelSheetChunkMapper chunkMapper;

    /**
     * 解析 Excel 文件并持久化到数据库（分块存储）
     * <p>
     * 执行流程：
     * <ol>
     *   <li>遍历所有 Sheet</li>
     *   <li>每个 Sheet 插入一条 excel_sheet 记录（存元信息 + 配置）</li>
     *   <li>按 CHUNK_SIZE 行批量构建 celldata JSON，逐块写入 excel_sheet_chunk</li>
     * </ol>
     *
     * @param is         Excel 文件输入流（.xlsx 或 .xls）
     * @param document   已持久化的文档实体（需含有效 ID）
     * @return 所有 Sheet 的元信息列表（不含 celldata，仅用于给调用方汇总）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ExcelSheet> parseAndSave(InputStream is, ExcelDocument document) throws Exception {
        List<ExcelSheet> savedSheets = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(is)) {
            int sheetTotal = workbook.getNumberOfSheets();
            log.info("开始解析文档 [{}], 共 {} 个 Sheet", document.getName(), sheetTotal);

            for (int si = 0; si < sheetTotal; si++) {
                Sheet sheet = workbook.getSheetAt(si);
                log.info("  → 解析 Sheet[{}]: {}", si, sheet.getSheetName());

                ExcelSheet sheetEntity = saveSheetMeta(sheet, document, si, sheetTotal);
                savedSheets.add(sheetEntity);

                saveSheetChunks(sheet, workbook, sheetEntity);
            }
        }

        log.info("文档 [{}] 解析完成，共 {} 个 Sheet", document.getName(), savedSheets.size());
        return savedSheets;
    }

    // =========================================================
    // 内部方法
    // =========================================================

    /**
     * 解析并保存一个 Sheet 的元信息（合并单元格、列宽等）
     */
    private ExcelSheet saveSheetMeta(Sheet sheet, ExcelDocument document, int sheetIndex, int totalSheets) {
        ExcelSheet sheetEntity = new ExcelSheet();
        sheetEntity.setDocumentId(document.getId());
        sheetEntity.setSheetIndex(sheetIndex);
        sheetEntity.setSheetName(sheet.getSheetName());
        sheetEntity.setActive(sheetIndex == 0 ? 1 : 0);
        sheetEntity.setStatus(1);

        // 统计行列数
        int maxRow = sheet.getLastRowNum();
        int maxCol = calcMaxCol(sheet);
        sheetEntity.setTotalRows(maxRow + 1);
        sheetEntity.setTotalCols(maxCol);

        // 合并单元格配置（通常很小，直接 JSON 存这里）
        JSONObject mergeConfig = buildMergeConfig(sheet);
        sheetEntity.setMergeConfigJson(mergeConfig.toJSONString());

        // 列宽配置
        JSONObject columnLen = buildColumnLen(sheet, maxCol);
        sheetEntity.setColumnLenJson(columnLen.toJSONString());

        JSONObject rowLen = buildRowLen(sheet);
        sheetEntity.setRowLenJson(rowLen.toJSONString());

        JSONObject config = new JSONObject();
        config.put("merge", mergeConfig);
        config.put("columnlen", columnLen);
        config.put("rowlen", rowLen);
        sheetEntity.setConfigJson(config.toJSONString());

        // chunkCount 先设 0，等分块写入后再更新
        sheetEntity.setChunkCount(0);

        sheetMapper.insert(sheetEntity);
        log.info("    Sheet 元信息已保存: id={}, rows={}, cols={}", sheetEntity.getId(), maxRow + 1, maxCol);
        return sheetEntity;
    }

    /**
     * 将一个 Sheet 的所有单元格数据按 CHUNK_SIZE 行分块，批量写入数据库
     */
    private void saveSheetChunks(Sheet sheet, Workbook workbook, ExcelSheet sheetEntity) {
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        int lastRowNum = sheet.getLastRowNum();

        // 当前块正在积累的 celldata 条目
        List<JSONObject> buffer = new ArrayList<>(CHUNK_SIZE * 10);
        int chunkIndex = 0;
        int chunkStartRow = 0;
        int chunkEndRow = 0;

        for (int rowIdx = 0; rowIdx <= lastRowNum; rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            for (Cell cell : row) {
                JSONObject vObj = buildCellValue(cell, evaluator);
                if (vObj != null) {
                    JSONObject cellItem = new JSONObject();
                    cellItem.put("r", cell.getRowIndex());
                    cellItem.put("c", cell.getColumnIndex());
                    cellItem.put("v", vObj);
                    buffer.add(cellItem);
                }
            }

            chunkEndRow = rowIdx;

            // 达到分块大小时，将 buffer 写入数据库
            boolean isChunkFull = (rowIdx - chunkStartRow + 1) >= CHUNK_SIZE;
            boolean isLastRow   = (rowIdx == lastRowNum);

            if ((isChunkFull || isLastRow) && !buffer.isEmpty()) {
                ExcelSheetChunk chunk = new ExcelSheetChunk();
                chunk.setDocumentId(sheetEntity.getDocumentId());
                chunk.setSheetId(sheetEntity.getId());
                chunk.setChunkIndex(chunkIndex);
                chunk.setRowStart(chunkStartRow);
                chunk.setRowEnd(chunkEndRow);
                chunk.setCelldataJson(JSONArray.toJSONString(buffer));
                chunkMapper.insert(chunk);

                log.debug("    Chunk[{}] 已写入: rows {}-{}, cellCount={}", chunkIndex, chunkStartRow, chunkEndRow, buffer.size());

                // 重置缓冲区
                buffer.clear();
                chunkIndex++;
                chunkStartRow = rowIdx + 1;
            } else if (isChunkFull) {
                // buffer 为空但行数达到阈值，只推进 chunkStartRow
                chunkStartRow = rowIdx + 1;
            }
        }

        // 更新 chunkCount
        ExcelSheet update = new ExcelSheet();
        update.setId(sheetEntity.getId());
        update.setChunkCount(chunkIndex);
        sheetMapper.updateById(update);
        sheetEntity.setChunkCount(chunkIndex);

        log.info("    Sheet [{}] 分块完成，共 {} 块", sheetEntity.getSheetName(), chunkIndex);
    }

    /**
     * 根据单元格类型构建 Luckysheet 的 v 对象
     */
    private JSONObject buildCellValue(Cell cell, FormulaEvaluator evaluator) {
        JSONObject v = new JSONObject();
        JSONObject ct = new JSONObject();

        try {
            switch (cell.getCellType()) {
                case STRING: {
                    String val = cell.getStringCellValue();
                    if (val == null || val.isEmpty()) return null;
                    v.put("v", val);
                    v.put("m", val);
                    ct.put("fa", "General");
                    ct.put("t", "s");
                    break;
                }
                case NUMERIC: {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        String dateStr = sdf.format(cell.getDateCellValue());
                        v.put("v", dateStr);
                        v.put("m", dateStr);
                        ct.put("fa", "yyyy-MM-dd");
                        ct.put("t", "d");
                    } else {
                        double num = cell.getNumericCellValue();
                        v.put("v", num);
                        v.put("m", formatNum(num));
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    }
                    break;
                }
                case BOOLEAN: {
                    boolean b = cell.getBooleanCellValue();
                    v.put("v", b);
                    v.put("m", b ? "TRUE" : "FALSE");
                    ct.put("fa", "General");
                    ct.put("t", "b");
                    break;
                }
                case FORMULA: {
                    try {
                        CellValue cv = evaluator.evaluate(cell);
                        v.put("f", "=" + cell.getCellFormula());
                        if (cv != null && cv.getCellType() == CellType.NUMERIC) {
                            v.put("v", cv.getNumberValue());
                            v.put("m", formatNum(cv.getNumberValue()));
                        } else {
                            String sv = cv != null ? cv.getStringValue() : "";
                            v.put("v", sv);
                            v.put("m", sv);
                        }
                        ct.put("fa", "General");
                        ct.put("t", "n");
                    } catch (Exception fe) {
                        // 公式计算失败时降级为字符串
                        String raw = cell.getCellFormula();
                        v.put("v", "=" + raw);
                        v.put("m", "=" + raw);
                        ct.put("fa", "General");
                        ct.put("t", "s");
                    }
                    break;
                }
                case BLANK:
                default:
                    return null;
            }
        } catch (Exception e) {
            // 单个单元格解析异常时跳过，不中断整体解析
            return null;
        }

        v.put("ct", ct);
        return v;
    }

    /**
     * 计算 Sheet 的最大列数
     */
    private int calcMaxCol(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) {
            if (row.getLastCellNum() > max) {
                max = row.getLastCellNum();
            }
        }
        return max;
    }

    /**
     * 构建合并单元格配置 JSON（Luckysheet merge 格式）
     */
    private JSONObject buildMergeConfig(Sheet sheet) {
        JSONObject mergeConfig = new JSONObject();
        for (CellRangeAddress range : sheet.getMergedRegions()) {
            String key = range.getFirstRow() + "_" + range.getFirstColumn();
            JSONObject item = new JSONObject();
            item.put("r", range.getFirstRow());
            item.put("c", range.getFirstColumn());
            item.put("rs", range.getLastRow() - range.getFirstRow() + 1);
            item.put("cs", range.getLastColumn() - range.getFirstColumn() + 1);
            mergeConfig.put(key, item);
        }
        return mergeConfig;
    }

    /**
     * 构建列宽配置 JSON（Luckysheet columnlen 格式）
     */
    private JSONObject buildColumnLen(Sheet sheet, int maxCol) {
        JSONObject colWidths = new JSONObject();
        try {
            for (int ci = 0; ci < maxCol; ci++) {
                double width = sheet.getColumnWidthInPixels(ci);
                colWidths.put(String.valueOf(ci), (int) Math.max(width, 72));
            }
        } catch (Exception ignored) {
            // 部分 sheet 可能无行数据，忽略
        }
        return colWidths;
    }

    private JSONObject buildRowLen(Sheet sheet) {
        JSONObject rowHeights = new JSONObject();
        for (Row row : sheet) {
            if (row == null) continue;
            float height = row.getHeightInPoints();
            if (height > 0) {
                rowHeights.put(String.valueOf(row.getRowNum()), Math.round(height / 0.75f));
            }
        }
        return rowHeights;
    }

    private String formatNum(double num) {
        if (num == (long) num) return String.valueOf((long) num);
        return String.valueOf(num);
    }
}
