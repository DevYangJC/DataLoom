package com.demo.excel.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.demo.excel.entity.ExcelSheet;
import com.demo.excel.entity.ExcelSheetChunk;
import com.demo.excel.mapper.ExcelSheetChunkMapper;
import com.demo.excel.mapper.ExcelSheetMapper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class ExcelSheetServiceTest {

    @Mock
    private ExcelSheetMapper sheetMapper;

    @Mock
    private ExcelSheetChunkMapper chunkMapper;

    @Mock
    private ExcelDocumentService documentService;

    @InjectMocks
    private ExcelSheetService sheetService;

    @Test
    public void replaceWorkbookRebuildsSheetsChunksAndDocumentMetadata() {
        JSONObject firstCell = new JSONObject();
        firstCell.put("v", "A1");
        firstCell.put("m", "A1");

        JSONObject secondCell = new JSONObject();
        secondCell.put("v", 42);
        secondCell.put("m", "42");

        JSONArray firstRow = new JSONArray();
        firstRow.add(firstCell);

        JSONArray secondRow = new JSONArray();
        secondRow.add(null);
        secondRow.add(secondCell);

        JSONObject config = new JSONObject();
        JSONObject columnLen = new JSONObject();
        columnLen.put("0", 120);
        config.put("columnlen", columnLen);

        JSONObject rowLen = new JSONObject();
        rowLen.put("0", 28);
        config.put("rowlen", rowLen);

        Map<String, Object> sheet = new java.util.LinkedHashMap<>();
        sheet.put("name", "Budget");
        sheet.put("index", "sheet-1");
        sheet.put("status", 1);
        sheet.put("order", 0);
        sheet.put("config", config);
        sheet.put("data", Arrays.asList(firstRow, secondRow));

        sheetService.replaceWorkbook(7L, Collections.singletonList(sheet));

        verify(chunkMapper).delete(any(Wrapper.class));
        verify(sheetMapper).update(any(ExcelSheet.class), any(Wrapper.class));

        ArgumentCaptor<ExcelSheet> sheetCaptor = ArgumentCaptor.forClass(ExcelSheet.class);
        verify(sheetMapper).insert(sheetCaptor.capture());
        ExcelSheet savedSheet = sheetCaptor.getValue();
        assertEquals(Long.valueOf(7L), savedSheet.getDocumentId());
        assertEquals("Budget", savedSheet.getSheetName());
        assertEquals(Integer.valueOf(1), savedSheet.getActive());
        assertEquals(Integer.valueOf(2), savedSheet.getTotalRows());
        assertEquals(Integer.valueOf(2), savedSheet.getTotalCols());
        assertEquals("{\"0\":120}", savedSheet.getColumnLenJson());
        assertEquals("{\"0\":28}", savedSheet.getRowLenJson());

        ArgumentCaptor<ExcelSheetChunk> chunkCaptor = ArgumentCaptor.forClass(ExcelSheetChunk.class);
        verify(chunkMapper, atLeastOnce()).insert(chunkCaptor.capture());
        ExcelSheetChunk chunk = chunkCaptor.getValue();
        assertEquals(Long.valueOf(7L), chunk.getDocumentId());
        assertNotNull(chunk.getCelldataJson());

        JSONArray celldata = JSONArray.parseArray(chunk.getCelldataJson());
        assertEquals(2, celldata.size());
        assertEquals(0, celldata.getJSONObject(0).getIntValue("r"));
        assertEquals(0, celldata.getJSONObject(0).getIntValue("c"));
        assertEquals(1, celldata.getJSONObject(1).getIntValue("r"));
        assertEquals(1, celldata.getJSONObject(1).getIntValue("c"));

        verify(documentService).updateSheetMeta(7L, 1, "[\"Budget\"]");
    }
}
