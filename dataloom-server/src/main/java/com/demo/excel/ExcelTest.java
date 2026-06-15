package com.demo.excel;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;

/**
 * Excel 本地解析验证工具（独立运行，不依赖 Spring 容器）
 * <p>
 * 仅用于本地调试，验证 POI 解析逻辑是否正确。
 * 生产解析请走 ExcelParserService（需 Spring 环境 + 数据库分块写入）。
 */
public class ExcelTest {

    public static void main(String[] args) {
        try {
            File file = new File("d:/Develop/Code/Work-Code-ZX/code/excel-demo/dataloom-server/upload/5e9182a0-2fa9-4dbe-b571-ab6aee145451_土木工程学院_2026年硕士研究生复试名单-公示.xlsx");
            if (!file.exists()) {
                System.out.println("File not found: " + file.getAbsolutePath());
                return;
            }
            System.out.println("File length: " + file.length() + " bytes");

            // 使用独立解析（不写库，只统计数据量）
            try (Workbook workbook = WorkbookFactory.create(new FileInputStream(file))) {
                int sheetCount = workbook.getNumberOfSheets();
                System.out.println("Sheet 数量: " + sheetCount);

                for (int si = 0; si < sheetCount; si++) {
                    Sheet sheet = workbook.getSheetAt(si);
                    int totalRows = sheet.getLastRowNum() + 1;
                    int maxCol = 0;
                    int cellCount = 0;

                    for (Row row : sheet) {
                        if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
                        for (Cell cell : row) {
                            if (cell.getCellType() != CellType.BLANK) cellCount++;
                        }
                    }

                    System.out.printf("Sheet[%d] %-20s | 行数: %6d | 列数: %3d | 有效单元格: %7d | 预计分块数(1000行/块): %d%n",
                            si, sheet.getSheetName(), totalRows, maxCol, cellCount,
                            (int) Math.ceil(totalRows / 1000.0));

                    // 打印前5行数据快照
                    System.out.println("  --- 前5行数据快照 ---");
                    int previewRows = Math.min(5, sheet.getLastRowNum() + 1);
                    FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
                    for (int ri = 0; ri < previewRows; ri++) {
                        Row row = sheet.getRow(ri);
                        if (row == null) continue;
                        StringBuilder sb = new StringBuilder("  Row[" + ri + "]: ");
                        for (Cell cell : row) {
                            sb.append("[").append(getCellValueAsString(cell, evaluator)).append("] ");
                        }
                        System.out.println(sb);
                    }

                    // 合并单元格信息
                    int mergeCount = sheet.getNumMergedRegions();
                    System.out.println("  合并单元格数: " + mergeCount);
                    System.out.println();
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 将单元格值转为字符串（用于调试输出）
     */
    private static String getCellValueAsString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING:  return cell.getStringCellValue();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                    }
                    double d = cell.getNumericCellValue();
                    return d == (long) d ? String.valueOf((long) d) : String.valueOf(d);
                case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    CellValue cv = evaluator.evaluate(cell);
                    return cv != null ? cv.formatAsString() : "";
                case BLANK:   return "";
                default:      return "?";
            }
        } catch (Exception e) {
            return "[ERR]";
        }
    }
}
