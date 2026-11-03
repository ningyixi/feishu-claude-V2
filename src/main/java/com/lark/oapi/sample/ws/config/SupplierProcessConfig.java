package com.lark.oapi.sample.ws.config;

import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@Configuration
public class SupplierProcessConfig {
    @Bean(destroyMethod="close")
    public ProcessEngine supplierProcessEngine(DataSource dataSource, PlatformTransactionManager transactionManager) {
        var configuration = new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        // Connector/J may otherwise search every visible catalog for ACT_* tables.
        // A second local store must never mistake another database's engine schema for its own.
        try (var connection = dataSource.getConnection()) {
            configuration.setDatabaseCatalog(connection.getCatalog());
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("无法确定工作流数据库", ex);
        }
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setAsyncExecutorActivate(false);
        configuration.setHistory("audit");
        configuration.setDisableIdmEngine(true);
        configuration.setDisableEventRegistry(true);
        return configuration.buildProcessEngine();
    }
}
