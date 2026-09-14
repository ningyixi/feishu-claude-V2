package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.DigitalEmployee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DigitalEmployeeRepository extends JpaRepository<DigitalEmployee, Long> {
}
