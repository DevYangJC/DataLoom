-- Excel Demo 建表脚本（H2 兼容语法）
-- 重新设计：文档表只存元数据，Sheet 和分块数据分表存储，解决十万级数据存储问题
-- 生产换 MySQL 时只需修改 application.yml 中的 datasource 配置

-- ==============================
-- 1. 文档主表（元数据）
-- ==============================
CREATE TABLE IF NOT EXISTS excel_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255)   NOT NULL,
    sheet_count     INT            DEFAULT 0,
    sheet_names     VARCHAR(2000),
    version         BIGINT         DEFAULT 1,
    status          INT            DEFAULT 1,
    file_path       VARCHAR(500),
    file_size       BIGINT         DEFAULT 0,
    creator_id      VARCHAR(64)    DEFAULT 'demo-user',
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);

-- ==============================
-- 2. Sheet 元信息表（一文档多 Sheet）
-- ==============================
CREATE TABLE IF NOT EXISTS excel_sheet (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id         BIGINT         NOT NULL,
    sheet_index         INT            DEFAULT 0,
    sheet_name          VARCHAR(255)   NOT NULL,
    total_rows          INT            DEFAULT 0,
    total_cols          INT            DEFAULT 0,
    chunk_count         INT            DEFAULT 0,
    merge_config_json   CLOB,
    column_len_json     CLOB,
    row_len_json        CLOB,
    config_json         CLOB,
    active              INT            DEFAULT 0,
    status              INT            DEFAULT 1,
    create_time         TIMESTAMP      DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE excel_sheet ADD COLUMN IF NOT EXISTS row_len_json CLOB;
ALTER TABLE excel_sheet ADD COLUMN IF NOT EXISTS config_json CLOB;

-- ==============================
-- 3. Sheet 数据分块表（每块约 1000 行）
-- ==============================
CREATE TABLE IF NOT EXISTS excel_sheet_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT         NOT NULL,
    sheet_id        BIGINT         NOT NULL,
    chunk_index     INT            DEFAULT 0,
    row_start       INT            DEFAULT 0,
    row_end         INT            DEFAULT 0,
    celldata_json   CLOB,
    create_time     TIMESTAMP      DEFAULT CURRENT_TIMESTAMP
);


-- ========================================================
-- 【MySQL 版建表语句（仅供参考）】
-- ========================================================
-- CREATE TABLE IF NOT EXISTS excel_document (
--     id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
--     name            VARCHAR(255)   NOT NULL COMMENT '文档名称',
--     sheet_count     INT            DEFAULT 0 COMMENT 'Sheet 数量',
--     sheet_names     VARCHAR(2000)  COMMENT 'Sheet 名称列表 JSON',
--     version         BIGINT         DEFAULT 1 COMMENT '乐观锁版本号',
--     status          TINYINT        DEFAULT 1 COMMENT '1正常 2回收站 3已删除',
--     file_path       VARCHAR(500)   COMMENT '原始文件路径',
--     file_size       BIGINT         DEFAULT 0 COMMENT '文件大小(字节)',
--     creator_id      VARCHAR(64)    DEFAULT 'demo-user' COMMENT '创建者',
--     create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
--     update_time     DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
--     INDEX idx_status (status),
--     INDEX idx_creator (creator_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel 文档主表（只存元数据）';
--
-- CREATE TABLE IF NOT EXISTS excel_sheet (
--     id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
--     document_id         BIGINT         NOT NULL COMMENT '所属文档 ID',
--     sheet_index         INT            DEFAULT 0 COMMENT 'Sheet 下标（0起始）',
--     sheet_name          VARCHAR(255)   NOT NULL COMMENT 'Sheet 名称',
--     total_rows          INT            DEFAULT 0 COMMENT '总行数',
--     total_cols          INT            DEFAULT 0 COMMENT '总列数',
--     chunk_count         INT            DEFAULT 0 COMMENT '分块数量',
--     merge_config_json   LONGTEXT       COMMENT '合并单元格配置 JSON',
--     column_len_json     LONGTEXT       COMMENT '列宽配置 JSON',
--     row_len_json        LONGTEXT       COMMENT '行高配置 JSON',
--     config_json         LONGTEXT       COMMENT 'Luckysheet 完整 config JSON',
--     active              TINYINT        DEFAULT 0 COMMENT '是否激活：1是 0否',
--     status              TINYINT        DEFAULT 1 COMMENT '1正常 3已删除',
--     create_time         DATETIME       DEFAULT CURRENT_TIMESTAMP,
--     update_time         DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
--     INDEX idx_document_id (document_id)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel Sheet 元信息表';
--
-- CREATE TABLE IF NOT EXISTS excel_sheet_chunk (
--     id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
--     document_id     BIGINT         NOT NULL COMMENT '所属文档 ID（冗余，方便级联删除）',
--     sheet_id        BIGINT         NOT NULL COMMENT '所属 Sheet ID',
--     chunk_index     INT            DEFAULT 0 COMMENT '块序号（0起始）',
--     row_start       INT            DEFAULT 0 COMMENT '起始行号（含）',
--     row_end         INT            DEFAULT 0 COMMENT '结束行号（含）',
--     celldata_json   LONGTEXT       COMMENT '单元格数据 JSON（Luckysheet celldata 格式）',
--     create_time     DATETIME       DEFAULT CURRENT_TIMESTAMP,
--     INDEX idx_sheet_id (sheet_id),
--     INDEX idx_document_id (document_id),
--     INDEX idx_chunk_index (sheet_id, chunk_index)
-- ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Excel Sheet 数据分块表（每块约1000行）';
