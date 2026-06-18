import json
import math
import logging
from typing import List, Dict, Any
from fastapi import APIRouter, Depends, HTTPException, Body
from sqlalchemy.orm import Session
from app.database import get_db
import app.services.document_service as document_service
import app.services.sheet_service as sheet_service
from app.schemas import RenameRequest, CellUpdateItem, WorkbookSaveRequest

logger = logging.getLogger(__name__)

# 创建路由组 (类似于 @RestController 和 @RequestMapping("/api/excel/document"))
router = APIRouter(prefix="/api/excel/document", tags=["document"])

def parse_json_or_empty(json_str: str, is_array: bool = False) -> Any:
    """安全的 JSON 解析函数，失败则返回空对象或空列表"""
    default_val = [] if is_array else {}
    if not json_str or not str(json_str).strip():
        return default_val
    try:
        parsed = json.loads(json_str)
        return parsed if parsed is not None else default_val
    except Exception:
        return default_val


# 相当于 @GetMapping("/list")
@router.get("/list")
async def list_documents(pageNum: int = 1, pageSize: int = 20, db: Session = Depends(get_db)):
    """
    获取分页的文档列表。
    """
    records, total = document_service.list_documents_paginated(pageNum, pageSize, db)
    pages = math.ceil(total / pageSize) if pageSize > 0 else 0

    formatted_records = []
    for doc in records:
        formatted_records.append({
            "id": doc.id,
            "name": doc.name,
            "sheetCount": doc.sheet_count,
            "sheetNames": doc.sheet_names,
            "version": doc.version,
            "fileSize": doc.file_size,
            "createTime": doc.create_time,
            "updateTime": doc.update_time
        })

    return {
        "code": 200,
        "success": True,
        "message": "success",
        "data": {
            "total": total,
            "pages": pages,
            "current": pageNum,
            "records": formatted_records
        }
    }


# 相当于 @GetMapping("/{id}")
@router.get("/{id}")
async def get_document_detail(id: int, db: Session = Depends(get_db)):
    """
    获取文档详情及其所有 Sheet 的元数据（不包含单元格数据）。
    """
    doc = document_service.get_document_by_id(id, db)
    if not doc or doc.status != 1:
        return {
            "code": 404,
            "success": False,
            "message": "文档不存在",
            "data": None
        }

    sheets = sheet_service.list_sheets_by_document_id(id, db)

    sheet_info_list = []
    for s in sheets:
        merge_config = parse_json_or_empty(s.merge_config_json)
        column_len = parse_json_or_empty(s.column_len_json)
        row_len = parse_json_or_empty(s.row_len_json)
        config = parse_json_or_empty(s.config_json)

        # 补全可能缺失的 config 配置
        if not config:
            config = {
                "merge": merge_config,
                "columnlen": column_len,
                "rowlen": row_len
            }

        sheet_info_list.append({
            "sheetId": s.id,
            "sheetIndex": s.sheet_index,
            "sheetName": s.sheet_name,
            "totalRows": s.total_rows,
            "totalCols": s.total_cols,
            "chunkCount": s.chunk_count,
            "active": s.active,
            "config": config,
            "mergeConfig": merge_config,
            "columnLen": column_len,
            "rowLen": row_len,
            "hyperlink": parse_json_or_empty(s.hyperlink_config_json),
            "images": parse_json_or_empty(s.images_config_json),
            "luckysheet_conditionformat_save": parse_json_or_empty(s.condition_format_json, is_array=True),
            "chart": parse_json_or_empty(s.chart_json, is_array=True)
        })

    return {
        "code": 200,
        "success": True,
        "message": "success",
        "data": {
            "id": doc.id,
            "name": doc.name,
            "sheetCount": doc.sheet_count,
            "version": doc.version,
            "sheets": sheet_info_list
        }
    }


# 相当于 @GetMapping("/{id}/sheet/{sheetId}/all")
@router.get("/{id}/sheet/{sheetId}/all")
async def load_all_celldata(id: int, sheetId: int, db: Session = Depends(get_db)):
    """
    加载指定 Sheet 的所有单元格数据，将所有分块数据合并后返回。
    """
    chunks = sheet_service.list_chunks_by_sheet_id(sheetId, db)

    merged_celldata = []
    for chunk in chunks:
        if chunk.celldata_json:
            try:
                cells = json.loads(chunk.celldata_json)
                if isinstance(cells, list):
                    merged_celldata.extend(cells)
            except Exception as e:
                logger.warn(f"解析 chunk {chunk.id} JSON 失败: {e}")

    return {
        "code": 200,
        "success": True,
        "message": "success",
        "data": {
            "sheetId": sheetId,
            "celldata": merged_celldata,
            "cellCount": len(merged_celldata)
        }
    }


# 相当于 @PutMapping("/{id}/cells/batch")
@router.put("/{id}/cells/batch")
async def batch_update_cells(id: int, updates: List[Dict[str, Any]] = Body(...), db: Session = Depends(get_db)):
    """
    批量增量更新单元格数据。
    """
    try:
        if not updates:
            return {
                "code": 200,
                "success": True,
                "message": "没有需要保存的修改",
                "data": None
            }

        sheet_service.batch_update_cells(id, updates, db)
        logger.info(f"文档 {id} 单元格批量更新成功，共 {len(updates)} 个修改")
        return {
            "code": 200,
            "success": True,
            "message": "保存成功",
            "data": None
        }
    except Exception as e:
        logger.error(f"文档 {id} 单元格批量更新失败: {e}")
        return {
            "code": 500,
            "success": False,
            "message": f"保存失败: {str(e)}",
            "data": None
        }


# 相当于 @PutMapping("/{id}/workbook")
@router.put("/{id}/workbook")
async def save_workbook(id: int, body: WorkbookSaveRequest = Body(...), db: Session = Depends(get_db)):
    """
    全量替换工作簿的 Sheet 和数据块。
    """
    try:
        sheets = body.sheets
        sheet_id_mapping = sheet_service.replace_workbook(id, sheets, db)
        logger.info(f"文档 {id} 工作簿全量替换成功，Sheet 数量: {len(sheets)}")

        return {
            "code": 200,
            "success": True,
            "message": "保存成功",
            "data": {
                "sheetCount": len(sheets),
                "sheetIdMap": sheet_id_mapping
            }
        }
    except Exception as e:
        logger.error(f"文档 {id} 工作簿全量替换失败: {e}", exc_info=True)
        return {
            "code": 500,
            "success": False,
            "message": f"保存失败: {str(e)}",
            "data": None
        }


# 相当于 @PutMapping("/{id}/name")
@router.put("/{id}/name")
async def rename_document(id: int, body: RenameRequest = Body(...), db: Session = Depends(get_db)):
    """
    重命名文档。
    """
    name = body.name
    if not name or not name.strip():
        return {
            "code": 500,
            "success": False,
            "message": "名称不能为空",
            "data": None
        }

    doc = document_service.get_document_by_id(id, db)
    if not doc or doc.status != 1:
        return {
            "code": 404,
            "success": False,
            "message": "文档不存在",
            "data": None
        }

    document_service.rename_document(id, name.strip(), db)
    logger.info(f"文档重命名成功: doc={id}, name={name.strip()}")
    return {
        "code": 200,
        "success": True,
        "message": "重命名成功",
        "data": None
    }


# 相当于 @DeleteMapping("/{id}")
@router.delete("/{id}")
async def delete_document(id: int, db: Session = Depends(get_db)):
    """
    删除文档的元数据和分块，并删除物理文件。
    """
    try:
        document_service.delete_document(id, db)
        sheet_service.delete_by_document_id(id, db)
        logger.info(f"文档删除成功: doc={id}")
        return {
            "code": 200,
            "success": True,
            "message": "删除成功",
            "data": None
        }
    except Exception as e:
        logger.error(f"删除文档 {id} 失败: {e}")
        return {
            "code": 500,
            "success": False,
            "message": f"删除失败: {str(e)}",
            "data": None
        }
