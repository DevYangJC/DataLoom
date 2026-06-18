import json
import logging
import math
from typing import List, Dict, Any, Tuple
from sqlalchemy.orm import Session
from app.models import ExcelSheet, ExcelSheetChunk, ExcelDocument
from app.config import CHUNK_SIZE
import app.services.document_service as document_service

logger = logging.getLogger(__name__)

def list_sheets_by_document_id(doc_id: int, db: Session) -> List[ExcelSheet]:
    return db.query(ExcelSheet).filter(
        ExcelSheet.document_id == doc_id,
        ExcelSheet.status == 1
    ).order_by(ExcelSheet.sheet_index.asc()).all()


def list_chunks_by_sheet_id(sheet_id: int, db: Session) -> List[ExcelSheetChunk]:
    return db.query(ExcelSheetChunk).filter(
        ExcelSheetChunk.sheet_id == sheet_id
    ).order_by(ExcelSheetChunk.chunk_index.asc()).all()


def delete_by_document_id(doc_id: int, db: Session):
    # Soft delete sheets
    sheets = db.query(ExcelSheet).filter(ExcelSheet.document_id == doc_id).all()
    for s in sheets:
        s.status = 3
    
    # Physical delete chunks
    db.query(ExcelSheetChunk).filter(ExcelSheetChunk.document_id == doc_id).delete()
    db.commit()


def batch_update_cells(doc_id: int, updates: List[Dict[str, Any]], db: Session):
    """
    Batch update cells: Group updates by chunk to minimize I/O.
    updates format: [{"sheetId": 1, "r": 0, "c": 1, "v": {...}}, ...]
    """
    if not updates:
        return

    # Group by sheetId and chunkIndex
    chunk_groups = {}
    for update in updates:
        sheet_id = int(update["sheetId"])
        r = int(update["r"])
        chunk_idx = r // CHUNK_SIZE
        key = f"{sheet_id}_{chunk_idx}"
        if key not in chunk_groups:
            chunk_groups[key] = []
        chunk_groups[key].append(update)

    try:
        # Process each group
        for key, group in chunk_groups.items():
            sheet_id_str, chunk_idx_str = key.split("_")
            sheet_id = int(sheet_id_str)
            target_chunk_idx = int(chunk_idx_str)

            chunk = db.query(ExcelSheetChunk).filter(
                ExcelSheetChunk.sheet_id == sheet_id,
                ExcelSheetChunk.chunk_index == target_chunk_idx
            ).first()

            is_new_chunk = False
            if chunk is None:
                is_new_chunk = True
                chunk = ExcelSheetChunk(
                    document_id=doc_id,
                    sheet_id=sheet_id,
                    chunk_index=target_chunk_idx,
                    row_start=target_chunk_idx * CHUNK_SIZE,
                    row_end=(target_chunk_idx + 1) * CHUNK_SIZE - 1,
                    celldata_json="[]"
                )
                cell_array = []
            else:
                cell_array = json.loads(chunk.celldata_json) if chunk.celldata_json else []

            # Perform the updates
            for update in group:
                r = int(update["r"])
                c = int(update["c"])
                v = update.get("v")

                found = False
                for idx, cell in enumerate(cell_array):
                    if cell.get("r") == r and cell.get("c") == c:
                        if is_empty_cell_value(v):
                            # Remove empty cell
                            cell_array.pop(idx)
                        else:
                            cell["v"] = v
                        found = True
                        break

                if not found and not is_empty_cell_value(v):
                    cell_array.append({
                        "r": r,
                        "c": c,
                        "v": v
                    })

            chunk.celldata_json = json.dumps(cell_array)

            if is_new_chunk:
                db.add(chunk)
                db.flush()
                # Update sheet's chunk_count if needed
                sheet = db.query(ExcelSheet).filter(ExcelSheet.id == sheet_id).first()
                if sheet and sheet.chunk_count <= target_chunk_idx:
                    sheet.chunk_count = target_chunk_idx + 1
                    db.add(sheet)
            else:
                db.add(chunk)
            
        db.commit()
    except Exception as e:
        db.rollback()
        logger.error(f"Error in batch_update_cells: {e}")
        raise e


def replace_workbook(doc_id: int, workbook_sheets: List[Dict[str, Any]], db: Session) -> Dict[int, int]:
    """
    Full sheet/chunk replacement. Transaction protected.
    Deletes all chunks and soft-deletes old sheets, then reconstructs the sheets.
    Returns mapping of {sheetIndex: newSheetId}.
    """
    if not workbook_sheets:
        raise ValueError("Workbook sheets must not be empty")

    try:
        # 1. Delete all chunks for this document
        db.query(ExcelSheetChunk).filter(ExcelSheetChunk.document_id == doc_id).delete()

        # 2. Soft delete sheets
        db.query(ExcelSheet).filter(ExcelSheet.document_id == doc_id).update({"status": 3})
        db.flush()

        sheet_names = []
        visible_index = 0
        sheet_id_mapping = {}

        for raw_sheet in workbook_sheets:
            if raw_sheet is None:
                continue

            name = raw_sheet.get("name")
            if not name or not str(name).strip():
                name = f"Sheet{visible_index + 1}"
            else:
                name = str(name).strip()
            sheet_names.append(name)

            # Extract configs
            config = raw_sheet.get("config")
            if not isinstance(config, dict):
                config = {}
            
            hyperlink = raw_sheet.get("hyperlink")
            if not isinstance(hyperlink, dict):
                hyperlink = {}
            
            images = raw_sheet.get("images")
            if not isinstance(images, dict):
                images = {}

            condition_format = raw_sheet.get("luckysheet_conditionformat_save")
            if not isinstance(condition_format, list):
                condition_format = []
            
            chart = raw_sheet.get("chart")
            if not isinstance(chart, list):
                chart = []

            merge = config.get("merge")
            if not isinstance(merge, dict):
                merge = {}
            
            column_len = config.get("columnlen")
            if not isinstance(column_len, dict):
                column_len = {}

            row_len = config.get("rowlen")
            if not isinstance(row_len, dict):
                row_len = {}

            config["merge"] = merge
            config["columnlen"] = column_len
            config["rowlen"] = row_len

            # Resolve celldata
            celldata = resolve_celldata(raw_sheet)
            
            total_rows = int_value(raw_sheet.get("row"), calc_total_rows(raw_sheet.get("data"), celldata))
            total_cols = int_value(raw_sheet.get("column"), calc_total_cols(raw_sheet.get("data"), celldata))

            # Create sheet record
            sheet = ExcelSheet(
                document_id=doc_id,
                sheet_index=visible_index,
                sheet_name=name,
                total_rows=max(total_rows, 1),
                total_cols=max(total_cols, 1),
                chunk_count=0,
                merge_config_json=json.dumps(merge),
                column_len_json=json.dumps(column_len),
                row_len_json=json.dumps(row_len),
                config_json=json.dumps(config),
                hyperlink_config_json=json.dumps(hyperlink),
                images_config_json=json.dumps(images),
                condition_format_json=json.dumps(condition_format),
                chart_json=json.dumps(chart),
                active=int_value(raw_sheet.get("status"), 1 if visible_index == 0 else 0),
                status=1
            )
            db.add(sheet)
            db.flush()

            sheet_id_mapping[visible_index] = sheet.id

            # Save chunks
            chunk_count = save_celldata_chunks(doc_id, sheet.id, celldata, sheet.total_rows, db)
            sheet.chunk_count = chunk_count
            db.add(sheet)
            db.flush()

            visible_index += 1

        if not sheet_names:
            raise ValueError("Workbook contains no valid sheets")

        # 3. Update document meta
        document_service.update_sheet_meta(doc_id, len(sheet_names), json.dumps(sheet_names), db)
        
        db.commit()
        return sheet_id_mapping
    except Exception as e:
        db.rollback()
        logger.error(f"Error in replace_workbook: {e}")
        raise e


# ==========================================
# Helpers
# ==========================================

def is_empty_cell_value(val) -> bool:
    if val is None:
        return True
    if isinstance(val, dict) and not val:
        return True
    if isinstance(val, list) and not val:
        return True
    return False


def int_value(val, default_val: int) -> int:
    if val is None:
        return default_val
    try:
        return int(float(val))
    except (ValueError, TypeError):
        return default_val


def resolve_celldata(raw_sheet: Dict[str, Any]) -> List[Dict[str, Any]]:
    celldata = raw_sheet.get("celldata")
    if isinstance(celldata, list) and celldata:
        return normalize_celldata(celldata)
    return data_matrix_to_celldata(raw_sheet.get("data"))


def normalize_celldata(celldata: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    normalized = []
    for cell in celldata:
        if not isinstance(cell, dict) or "r" not in cell or "c" not in cell:
            continue
        v = cell.get("v")
        if is_empty_cell_value(v):
            continue
        normalized.append(cell)
    return normalized


def data_matrix_to_celldata(data: Any) -> List[Dict[str, Any]]:
    celldata = []
    if not isinstance(data, list):
        return celldata

    for r, row in enumerate(data):
        if not isinstance(row, list):
            continue
        for c, val in enumerate(row):
            if is_empty_cell_value(val):
                continue
            celldata.append({
                "r": r,
                "c": c,
                "v": val
            })
    return celldata


def calc_total_rows(data: Any, celldata: List[Dict[str, Any]]) -> int:
    max_rows = len(data) if isinstance(data, list) else 0
    for cell in celldata:
        max_rows = max(max_rows, int(cell.get("r", 0)) + 1)
    return max_rows


def calc_total_cols(data: Any, celldata: List[Dict[str, Any]]) -> int:
    max_cols = 0
    if isinstance(data, list):
        for row in data:
            if isinstance(row, list):
                max_cols = max(max_cols, len(row))
    for cell in celldata:
        max_cols = max(max_cols, int(cell.get("c", 0)) + 1)
    return max_cols


def save_celldata_chunks(doc_id: int, sheet_id: int, celldata: List[Dict[str, Any]], total_rows: int, db: Session) -> int:
    chunks = {}
    for cell in celldata:
        r = int(cell["r"])
        chunk_idx = r // CHUNK_SIZE
        if chunk_idx not in chunks:
            chunks[chunk_idx] = []
        chunks[chunk_idx].append(cell)

    if not chunks:
        # Return at least 1 chunk
        return max(1, math.ceil(max(total_rows, 1) / CHUNK_SIZE))

    for chunk_idx, cells in chunks.items():
        chunk = ExcelSheetChunk(
            document_id=doc_id,
            sheet_id=sheet_id,
            chunk_index=chunk_idx,
            row_start=chunk_idx * CHUNK_SIZE,
            row_end=(chunk_idx + 1) * CHUNK_SIZE - 1,
            celldata_json=json.dumps(cells)
        )
        db.add(chunk)
        db.flush()

    return max(chunks.keys()) + 1
