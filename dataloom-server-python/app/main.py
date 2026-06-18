import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.database import engine, Base
from app.routers import file, document

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

# Initialize database tables (Zero configuration - creates tables if they don't exist)
Base.metadata.create_all(bind=engine)
logger.info("Database tables initialized successfully.")

# Create FastAPI application
app = FastAPI(
    title="DataLoom Python Backend",
    description="Python version of DataLoom backend matching Spring Boot REST APIs",
    version="1.0.0"
)

# Add CORS Middleware to support local dev client access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers
app.include_router(file.router)
app.include_router(document.router)

@app.get("/")
def read_root():
    return {
        "name": "DataLoom Python REST Backend",
        "status": "Running",
        "engine": "FastAPI"
    }

if __name__ == "__main__":
    import uvicorn
    from app.config import HOST, PORT
    logger.info(f"Starting DataLoom server on http://{HOST}:{PORT}")
    uvicorn.run("app.main:app", host=HOST, port=PORT, reload=True)
