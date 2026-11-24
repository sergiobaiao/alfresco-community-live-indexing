package org.acs.liveindexing;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.alfresco.database.connector.AlfrescoMetadataRepository;
import org.alfresco.database.connector.model.AlfrescoNode;
import org.alfresco.elasticsearch.batchindexing.readers.AlfrescoExplicitIdSetItemReader;
import org.alfresco.metrics.ReindexingMetrics;
import org.alfresco.reindexing.config.ReindexingCoreProperties;
import org.alfresco.reindexing.config.utils.StepConfigurer;
import org.alfresco.reindexing.listeners.FilterCountingListener;
import org.alfresco.reindexing.listeners.JobExitStatusAggregatorListener;
import org.alfresco.reindexing.listeners.JobLifecycleListener;
import org.alfresco.reindexing.listeners.ReadCountingListener;
import org.alfresco.reindexing.listeners.ReindexMetricsStepListener;
import org.alfresco.reindexing.listeners.ReindexSummaryStepListener;
import org.alfresco.reindexing.listeners.StepLifecycleListener;
import org.alfresco.reindexing.listeners.WriteMetricsFlushListener;
import org.alfresco.reindexing.model.ReindexingEvent;
import org.alfresco.reindexing.readers.PageReadFailureListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.ChunkOrientedStepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * {@code liveIndex} job: the upstream DLQ reindex step ({@code OneShotReindexDlqConfig.reindexDlqStep})
 * with the node ids coming from events instead of the dead-letter index. Reader, processors and writer
 * are upstream beans, so documents match the batch indexer exactly.
 */
@Configuration
class LiveIndexJobConfig
{
    /** Ids for the next run; set by the single flusher thread right before a synchronous launch. */
    @Bean
    AtomicReference<Set<Long>> liveIndexTargetIds()
    {
        return new AtomicReference<>(Set.of());
    }

    @Bean
    Job liveIndex(JobRepository jobRepository,
            JobExitStatusAggregatorListener jobExitStatusAggregatorListener,
            JobLifecycleListener jobLifecycleListener,
            Step liveIndexStep)
    {
        return new JobBuilder("liveIndex", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExitStatusAggregatorListener)
                .listener(jobLifecycleListener)
                .start(liveIndexStep)
                .build();
    }

    @Bean
    Step liveIndexStep(JobRepository jobRepository,
            ReindexingCoreProperties properties,
            ItemStreamReader<AlfrescoNode> liveIndexReader,
            ItemProcessor<AlfrescoNode, ReindexingEvent> processors,
            @Qualifier("writer") ItemWriter<ReindexingEvent> writer,
            ThreadPoolTaskExecutor reindexTaskExecutor,
            ExponentialBackOff exponentialBackOff,
            StepLifecycleListener stepLifecycleListener,
            ReindexMetricsStepListener reindexMetricsStepListener,
            ReindexSummaryStepListener reindexSummaryStepListener,
            ReadCountingListener readCountingListener,
            FilterCountingListener filterCountingListener,
            WriteMetricsFlushListener writeMetricsFlushListener,
            ReindexingMetrics reindexingMetrics,
            ObjectProvider<SkipListener<?, ?>> skipListenerProvider)
    {
        String stepName = "liveIndexStep";
        var builder = new ChunkOrientedStepBuilder<AlfrescoNode, ReindexingEvent>(stepName, jobRepository, properties.getBatchSize())
                .listener(stepLifecycleListener)
                .listener(reindexMetricsStepListener)
                .listener(reindexSummaryStepListener)
                .listener(readCountingListener)
                .listener(filterCountingListener)
                .listener(writeMetricsFlushListener)
                .reader(liveIndexReader)
                .processor(processors)
                .writer(writer);
        StepConfigurer.withRetrySkipAndMultithreadSettings(
                builder, stepName, properties, exponentialBackOff, reindexTaskExecutor, reindexingMetrics, skipListenerProvider);
        return builder.build();
    }

    @Bean
    @StepScope
    ItemStreamReader<AlfrescoNode> liveIndexReader(AlfrescoMetadataRepository metadataRepository,
            ReindexingCoreProperties properties,
            AtomicReference<Set<Long>> liveIndexTargetIds,
            ObjectProvider<PageReadFailureListener> pageReadFailureListenerProvider)
    {
        var reader = new AlfrescoExplicitIdSetItemReader(
                metadataRepository, liveIndexTargetIds.get(), pageReadFailureListenerProvider.getIfAvailable());
        reader.setPageSize(properties.getPageSize());
        reader.setName("liveIndexReader");
        return reader;
    }
}
