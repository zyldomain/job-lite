/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.elasticjob.lite.api;

import com.google.common.base.Optional;
import io.elasticjob.lite.api.listener.AbstractDistributeOnceElasticJobListener;
import io.elasticjob.lite.api.listener.ElasticJobListener;
import io.elasticjob.lite.api.script.ScriptJob;
import io.elasticjob.lite.api.strategy.JobInstance;
import io.elasticjob.lite.config.LiteJobConfiguration;
import io.elasticjob.lite.event.JobEventBus;
import io.elasticjob.lite.event.JobEventConfiguration;
import io.elasticjob.lite.exception.JobConfigurationException;
import io.elasticjob.lite.exception.JobSystemException;
import io.elasticjob.lite.executor.JobFacade;
import io.elasticjob.lite.internal.guarantee.GuaranteeService;
import io.elasticjob.lite.internal.schedule.*;
import io.elasticjob.lite.reg.base.CoordinatorRegistryCenter;
import lombok.Getter;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 作业调度器.
 * 
 * @author zhangliang
 * @author caohao
 */
public class JobScheduler {
    
    public static final String ELASTIC_JOB_DATA_MAP_KEY = "elasticJob";
    
    private static final String JOB_FACADE_DATA_MAP_KEY = "jobFacade";
    
    private final LiteJobConfiguration liteJobConfig;
    
    private final CoordinatorRegistryCenter regCenter;
    
    // TODO 为测试使用,测试用例不能反复new monitor service,以后需要把MonitorService重构为单例
    @Getter
    private final SchedulerFacade schedulerFacade;
    
    private final JobFacade jobFacade;
    
    public JobScheduler(final CoordinatorRegistryCenter regCenter, final LiteJobConfiguration liteJobConfig, final ElasticJobListener... elasticJobListeners) {
        this(regCenter, liteJobConfig, new JobEventBus(), elasticJobListeners);
    }
    
    public JobScheduler(final CoordinatorRegistryCenter regCenter, final LiteJobConfiguration liteJobConfig, final JobEventConfiguration jobEventConfig,
                        final ElasticJobListener... elasticJobListeners) {
        this(regCenter, liteJobConfig, new JobEventBus(jobEventConfig), elasticJobListeners);
    }
    
    private JobScheduler(final CoordinatorRegistryCenter regCenter, final LiteJobConfiguration liteJobConfig, final JobEventBus jobEventBus, final ElasticJobListener... elasticJobListeners) {
        //为当前的job添加一个实例
        JobRegistry.getInstance().addJobInstance(liteJobConfig.getJobName(), new JobInstance());
        //保存job的配置
        this.liteJobConfig = liteJobConfig;
        //保存注册中心
        this.regCenter = regCenter;
        //获取用户自定义的监听器
        List<ElasticJobListener> elasticJobListenerList = Arrays.asList(elasticJobListeners);

        setGuaranteeServiceForElasticJobListeners(regCenter, elasticJobListenerList);
        //定时器的门面类
        schedulerFacade = new SchedulerFacade(regCenter, liteJobConfig.getJobName(), elasticJobListenerList);
        //job的门面类
        jobFacade = new LiteJobFacade(regCenter, liteJobConfig.getJobName(), Arrays.asList(elasticJobListeners), jobEventBus);
    }
    
    private void setGuaranteeServiceForElasticJobListeners(final CoordinatorRegistryCenter regCenter, final List<ElasticJobListener> elasticJobListeners) {
        GuaranteeService guaranteeService = new GuaranteeService(regCenter, liteJobConfig.getJobName());
        for (ElasticJobListener each : elasticJobListeners) {
            if (each instanceof AbstractDistributeOnceElasticJobListener) {
                ((AbstractDistributeOnceElasticJobListener) each).setGuaranteeService(guaranteeService);
            }
        }
    }
    
    /**
     * 初始化作业.
     */
    public void init() {
        LiteJobConfiguration liteJobConfigFromRegCenter = schedulerFacade.updateJobConfiguration(liteJobConfig);
        //设置分片的个数
        JobRegistry.getInstance().setCurrentShardingTotalCount(liteJobConfigFromRegCenter.getJobName(), liteJobConfigFromRegCenter.getTypeConfig().getCoreConfig().getShardingTotalCount());
        //定时controller
        JobScheduleController jobScheduleController = new JobScheduleController(
                createScheduler(), createJobDetail(liteJobConfigFromRegCenter.getTypeConfig().getJobClass()), liteJobConfigFromRegCenter.getJobName());
        //将定时任务注册到zookeeper上
        JobRegistry.getInstance().registerJob(liteJobConfigFromRegCenter.getJobName(), jobScheduleController, regCenter);
        //在zookeeper上注册当前启动实例的一些信息
        schedulerFacade.registerStartUpInfo(!liteJobConfigFromRegCenter.isDisabled());
        //启动定时器
        jobScheduleController.scheduleJob(liteJobConfigFromRegCenter.getTypeConfig().getCoreConfig().getCron());
    }
    
    private JobDetail createJobDetail(final String jobClass) {
        //创建一个jobDetail，核心是LiteJob
        JobDetail result = JobBuilder.newJob(LiteJob.class).withIdentity(liteJobConfig.getJobName()).build();
        //保存在执行定时任务的时候需要向LiteJob中注入的字段的值
        result.getJobDataMap().put(JOB_FACADE_DATA_MAP_KEY, jobFacade);
        Optional<ElasticJob> elasticJobInstance = createElasticJobInstance();
        if (elasticJobInstance.isPresent()) {
            result.getJobDataMap().put(ELASTIC_JOB_DATA_MAP_KEY, elasticJobInstance.get());
        } else if (!jobClass.equals(ScriptJob.class.getCanonicalName())) {
            try {
                result.getJobDataMap().put(ELASTIC_JOB_DATA_MAP_KEY, Class.forName(jobClass).newInstance());
            } catch (final ReflectiveOperationException ex) {
                throw new JobConfigurationException("Elastic-Job: Job class '%s' can not initialize.", jobClass);
            }
        }
        return result;
    }
    
    protected Optional<ElasticJob> createElasticJobInstance() {
        return Optional.absent();
    }
    
    private Scheduler createScheduler() {
        Scheduler result;
        try {
            StdSchedulerFactory factory = new StdSchedulerFactory();
            factory.initialize(getBaseQuartzProperties());
            result = factory.getScheduler();
            result.getListenerManager().addTriggerListener(schedulerFacade.newJobTriggerListener());
        } catch (final SchedulerException ex) {
            throw new JobSystemException(ex);
        }
        return result;
    }
    
    private Properties getBaseQuartzProperties() {
        Properties result = new Properties();
        result.put("org.quartz.threadPool.class", org.quartz.simpl.SimpleThreadPool.class.getName());
        result.put("org.quartz.threadPool.threadCount", "1");
        result.put("org.quartz.scheduler.instanceName", liteJobConfig.getJobName());
        result.put("org.quartz.jobStore.misfireThreshold", "1");
        result.put("org.quartz.plugin.shutdownhook.class", JobShutdownHookPlugin.class.getName());
        result.put("org.quartz.plugin.shutdownhook.cleanShutdown", Boolean.TRUE.toString());
        return result;
    }
}
