package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EmployeeMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmployeeMessageRepository extends JpaRepository<EmployeeMessage, Long> {

    List<EmployeeMessage> findTop100ByEmployeeIdOrderByCreatedAtDesc(Long employeeId);

    long countByEmployeeIdAndReadFlagFalse(Long employeeId);

    long countByReadFlagFalse();

    void deleteByEmployeeId(Long employeeId);

    /** 全部标记已读（打开员工聊天框时调用） */
    @Modifying
    @Query("update EmployeeMessage m set m.readFlag = true where m.employeeId = :empId and m.readFlag = false")
    int markAllRead(@Param("empId") Long empId);
}
