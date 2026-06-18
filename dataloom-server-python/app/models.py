from sqlalchemy import Column, Integer, String, BigInteger, Text, DateTime, func
from app.database import Base

class ExcelDocument(Base):
    __tablename__ = "excel_document"

    id = Column(Integer, primary_key=True, autoincrement=True)
    name = Column(String(255), nullable=False)
    sheet_count = Column(Integer, default=0)
    sheet_names = Column(String(2000))  # JSON 列表作为字符串存储，例如 '["Sheet1", "Sheet2"]'
    version = Column(BigInteger, default=1)
    status = Column(Integer, default=1)  # 1 正常, 2 回收站, 3 已删除
    file_path = Column(String(500))
    file_size = Column(BigInteger, default=0)
    creator_id = Column(String(64), default="demo-user")
    create_time = Column(DateTime, server_default=func.now())
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now())


class ExcelSheet(Base):
    __tablename__ = "excel_sheet"

    id = Column(Integer, primary_key=True, autoincrement=True)
    document_id = Column(Integer, nullable=False)
    sheet_index = Column(Integer, default=0)
    sheet_name = Column(String(255), nullable=False)
    total_rows = Column(Integer, default=0)
    total_cols = Column(Integer, default=0)
    chunk_count = Column(Integer, default=0)
    
    # 存储配置的 JSON 字符串
    merge_config_json = Column(Text)       # 合并单元格配置
    column_len_json = Column(Text)         # 列宽配置
    row_len_json = Column(Text)            # 行高配置
    config_json = Column(Text)             # 完整的 config 配置 (包含 merge, columnlen, rowlen)
    hyperlink_config_json = Column(Text)   # 超链接配置
    images_config_json = Column(Text)      # 图片配置
    condition_format_json = Column(Text)   # 条件格式配置
    chart_json = Column(Text)              # 图表配置
    
    active = Column(Integer, default=0)    # 1 激活, 0 未激活
    status = Column(Integer, default=1)    # 1 正常, 3 已删除
    create_time = Column(DateTime, server_default=func.now())
    update_time = Column(DateTime, server_default=func.now(), onupdate=func.now())


class ExcelSheetChunk(Base):
    __tablename__ = "excel_sheet_chunk"

    id = Column(Integer, primary_key=True, autoincrement=True)
    document_id = Column(Integer, nullable=False)
    sheet_id = Column(Integer, nullable=False)
    chunk_index = Column(Integer, default=0)
    row_start = Column(Integer, default=0)
    row_end = Column(Integer, default=0)
    celldata_json = Column(Text)           # 单元格数据 JSON 列表，例如 '[{"r":0,"c":1,"v":{...}}]'
    create_time = Column(DateTime, server_default=func.now())
