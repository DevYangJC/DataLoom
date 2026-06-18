from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, DeclarativeBase
from app.config import DATABASE_URL

# 创建数据库引擎
# connect_args={"check_same_thread": False} 仅在 SQLite 下需要，支持多线程共享连接
connect_args = {"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}
engine = create_engine(DATABASE_URL, connect_args=connect_args)

# 创建会话工厂 (相当于 MyBatis 中的 SqlSessionFactory)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

# 模型基类 (所有实体类都需要继承它)
class Base(DeclarativeBase):
    pass

# FastAPI 依赖项：获取数据库会话，并在请求结束后自动关闭 (类似 Spring 中的事务管理与会话注入)
def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
