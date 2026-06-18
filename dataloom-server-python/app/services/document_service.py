import os
import logging
from typing import Optional, List, Tuple
from sqlalchemy.orm import Session
from sqlalchemy import desc
from app.models import ExcelDocument

logger = logging.getLogger(__name__)

def create_document(name: str, file_path: str, file_size: int, db: Session) -> ExcelDocument:
    # 相当于 MyBatis-Plus 的 baseMapper.insert(entity)
    doc = ExcelDocument(
        name=name,
        file_path=file_path,
        file_size=file_size,
        sheet_count=0,
        status=1,
        version=1,
        creator_id="demo-user"
    )
    db.add(doc)
    db.commit()
    db.refresh(doc)
    return doc

def update_sheet_meta(doc_id: int, sheet_count: int, sheet_names: str, db: Session) -> Optional[ExcelDocument]:
    doc = db.query(ExcelDocument).filter(ExcelDocument.id == doc_id).first()
    if doc:
        doc.sheet_count = sheet_count
        doc.sheet_names = sheet_names
        db.commit()
        db.refresh(doc)
    return doc

def get_document_by_id(doc_id: int, db: Session) -> Optional[ExcelDocument]:
    # 相当于 MyBatis-Plus 的 baseMapper.selectById(id)
    return db.query(ExcelDocument).filter(ExcelDocument.id == doc_id).first()

def list_documents_paginated(page_num: int, page_size: int, db: Session) -> Tuple[List[ExcelDocument], int]:
    # 相当于 MyBatis-Plus 的 baseMapper.selectPage()
    # status = 1 表示状态正常
    query = db.query(ExcelDocument).filter(ExcelDocument.status == 1)
    total = query.count()
    
    # page_num 是从 1 开始的，转换为 offset
    offset = (page_num - 1) * page_size
    records = query.order_by(desc(ExcelDocument.update_time)).offset(offset).limit(page_size).all()
    return records, total

def rename_document(doc_id: int, new_name: str, db: Session) -> Optional[ExcelDocument]:
    doc = db.query(ExcelDocument).filter(ExcelDocument.id == doc_id).first()
    if doc:
        doc.name = new_name
        db.commit()
        db.refresh(doc)
    return doc

def delete_document(doc_id: int, db: Session):
    doc = db.query(ExcelDocument).filter(ExcelDocument.id == doc_id).first()
    if doc:
        # 1. 删除本地物理文件
        if doc.file_path and os.path.exists(doc.file_path):
            try:
                os.remove(doc.file_path)
            except Exception as e:
                logger.warn(f"无法删除物理文件 {doc.file_path}: {e}")

        # 2. 软删除数据库记录 (将状态置为 3)
        doc.status = 3
        db.commit()
