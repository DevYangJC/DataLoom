import uuid
import shutil
import logging
import json
from fastapi import APIRouter, UploadFile, File, Depends
from sqlalchemy.orm import Session
from app.database import get_db
from app.config import UPLOAD_DIR
import app.services.document_service as document_service
import app.services.parser_service as parser_service

logger = logging.getLogger(__name__)

# 创建路由组 (类似于 @RestController 和 @RequestMapping("/api/excel"))
router = APIRouter(prefix="/api/excel", tags=["file"])

# 相当于 @PostMapping("/upload")
@router.post("/upload")
async def upload_file(file: UploadFile = File(...), db: Session = Depends(get_db)):
    """
    上传 Excel 文件 (.xlsx / .xls)。
    保存到上传目录，并进行分块解析，最后保存元数据。
    """
    try:
        original_name = file.filename
        saved_name = f"{uuid.uuid4()}_{original_name}"
        file_path = UPLOAD_DIR / saved_name

        # 将原始文件保存到磁盘
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        file_size = file_path.stat().st_size

        # 创建文档的数据库记录
        doc = document_service.create_document(
            name=original_name,
            file_path=str(file_path),
            file_size=file_size,
            db=db
        )

        # 解析工作簿并保存 Sheet / 数据块
        sheets = parser_service.parse_and_save(
            file_path=str(file_path),
            document=doc,
            db=db
        )

        # 更新文档的 Sheet 信息
        sheet_names = [s.sheet_name for s in sheets]
        document_service.update_sheet_meta(
            doc_id=doc.id,
            sheet_count=len(sheets),
            sheet_names=json.dumps(sheet_names),
            db=db
        )

        # 构建 Sheet 元数据列表 (排除 celldata 以减少响应体大小)
        sheet_info_list = []
        for s in sheets:
            sheet_info_list.append({
                "sheetId": s.id,
                "sheetIndex": s.sheet_index,
                "sheetName": s.sheet_name,
                "totalRows": s.total_rows,
                "totalCols": s.total_cols,
                "chunkCount": s.chunk_count,
                "active": s.active
            })

        logger.info(f"文件上传与解析成功: {original_name} -> docId={doc.id}")

        # 返回统一格式的响应体 (相当于 Java 的 ApiResponse)
        return {
            "code": 200,
            "success": True,
            "message": "上传成功",
            "data": {
                "documentId": doc.id,
                "name": doc.name,
                "sheetCount": len(sheets),
                "sheets": sheet_info_list
            }
        }
    except Exception as e:
        logger.error(f"上传与解析失败: {e}", exc_info=True)
        return {
            "code": 500,
            "success": False,
            "message": f"上传失败: {str(e)}",
            "data": None
        }
