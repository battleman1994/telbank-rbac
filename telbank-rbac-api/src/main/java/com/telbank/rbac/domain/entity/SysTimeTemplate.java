package com.telbank.rbac.domain.entity;

import java.time.LocalTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 角色时间模板（A21，{@code sys_time_template}）。
 *
 * <p>全局表，不按业务系统隔离；角色通过 {@code sys_role.time_template_id} 绑定，最多绑定 1 个模板。
 * 未绑定模板的角色即「永久角色」：管理员可直接分配，但不可自助申请。</p>
 *
 * <p>{@code start_time/end_time} 为模板时区下的本地墙钟时间，数据库列类型 TIME；
 * 模板本身只描述相对锚点日期的窗口，实际绝对窗口在写入用户角色 / 审批时计算。</p>
 */
@TableName("sys_time_template")
public class SysTimeTemplate extends BaseEntity {

    @TableField("name")
    private String name;

    @TableField("timezone")
    private String timezone;

    @TableField("start_day_offset")
    private Integer startDayOffset = 0;

    @TableField("start_time")
    private LocalTime startTime;

    @TableField("end_day_offset")
    private Integer endDayOffset = 0;

    @TableField("end_time")
    private LocalTime endTime;

    @TableField("status")
    private Integer status = 1;

    @TableField(value = "remark", updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Integer getStartDayOffset() {
        return startDayOffset;
    }

    public void setStartDayOffset(Integer startDayOffset) {
        this.startDayOffset = startDayOffset;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public Integer getEndDayOffset() {
        return endDayOffset;
    }

    public void setEndDayOffset(Integer endDayOffset) {
        this.endDayOffset = endDayOffset;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
