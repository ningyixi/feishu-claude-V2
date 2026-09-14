package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EmployeeDemand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmployeeDemandRepository extends JpaRepository<EmployeeDemand, Long> {

    List<EmployeeDemand> findByEmployeeIdOrderByCreatedAtAsc(Long employeeId);

    List<EmployeeDemand> findByEmployeeIdAndStatusInOrderByCreatedAtAsc(Long employeeId, List<String> statuses);

    List<EmployeeDemand> findByStatus(String status);

    void deleteByEmployeeId(Long employeeId);
}
