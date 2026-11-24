package org.acs.liveindexing;

import org.alfresco.elasticsearch.batchindexing.BatchIndexingApp;
import org.alfresco.elasticsearch.batchindexing.OneShotJobRunner;
import org.alfresco.reindexing.config.DataSourcesConfig;
import org.alfresco.reindexing.config.ElasticsearchDatasourceBatchConfig;
import org.alfresco.reindexing.config.ElasticsearchReindexByDateJobConfig;
import org.alfresco.reindexing.config.ElasticsearchReindexByIdJobConfig;
import org.alfresco.reindexing.config.MetadataRepositoryConfig;
import org.alfresco.reindexing.config.PrefixNamespaceConfig;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.RetryBackOffConfig;
import org.alfresco.reindexing.config.StandaloneReindexNodeRole;
import org.alfresco.reindexing.writers.RepositoryDescriptorItemWriter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the upstream Alfresco Search Community batch-indexing context (same imports as
 * {@link BatchIndexingApp}) and adds the event-driven trigger on top.
 * <p>
 * {@code alfresco.reindex.jobName=liveIndex} (application.properties) removes the upstream watermark
 * store and continuous scheduler, so this service never touches the batch indexer's cursor. The same
 * property activates {@link OneShotJobRunner}, which would run a job and exit, so it is excluded.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ComponentScan(
        basePackages = {"org.acs.liveindexing", "org.alfresco.elasticsearch.batchindexing"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {BatchIndexingApp.class, OneShotJobRunner.class}))
@EnableConfigurationProperties(ReindexingCoreProperties.class)
@Import({
        StandaloneReindexNodeRole.class,
        ElasticsearchReindexByDateJobConfig.class,
        ElasticsearchReindexByIdJobConfig.class,
        PrefixNamespaceConfig.class,
        RetryBackOffConfig.class,
        DataSourcesConfig.class,
        ElasticsearchDatasourceBatchConfig.class,
        MetadataRepositoryConfig.class,
        RepositoryDescriptorItemWriter.class,
})
@EnableJms
@EnableScheduling
public class LiveIndexingApp
{
    public static void main(String[] args)
    {
        SpringApplication.run(LiveIndexingApp.class, args);
    }
}
