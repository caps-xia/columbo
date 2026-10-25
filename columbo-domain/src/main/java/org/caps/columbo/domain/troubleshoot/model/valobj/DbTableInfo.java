package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 库表信息（listTables 返回项） */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DbTableInfo {

    /** 表名 */
    private String name;

    /** 表注释（建表 comment） */
    private String comment;
}
