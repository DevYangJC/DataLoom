from pydantic import BaseModel
from typing import List, Dict, Any, Optional, Union

# 用于校验请求体的 Pydantic 模型 (类似于 Java 中的 DTO 类)

class RenameRequest(BaseModel):
    name: str

class CellUpdateItem(BaseModel):
    sheetId: Union[int, str]
    r: int
    c: int
    v: Optional[Dict[str, Any]] = None

class WorkbookSaveRequest(BaseModel):
    sheets: List[Dict[str, Any]]
