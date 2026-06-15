package com.demo.excel.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器
 * <p>
 * 在实体中标注了 {@code @TableField(fill = FieldFill.INSERT)} 或
 * {@code @TableField(fill = FieldFill.INSERT_UPDATE)} 的字段，
 * 由此处理器在 INSERT/UPDATE 时自动赋值，无需业务代码手动 set。
 */
@Component
public class MybatisPlusMetaHandler implements MetaObjectHandler {

    /**
     * 插入时自动填充 createTime、updateTime
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
    }

    /**
     * 更新时自动填充 updateTime
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
