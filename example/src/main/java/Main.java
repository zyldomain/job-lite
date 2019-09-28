import io.elasticjob.lite.api.JobScheduler;
import io.elasticjob.lite.config.JobCoreConfiguration;
import io.elasticjob.lite.config.LiteJobConfiguration;
import io.elasticjob.lite.config.simple.SimpleJobConfiguration;
import io.elasticjob.lite.reg.base.CoordinatorRegistryCenter;
import io.elasticjob.lite.reg.zookeeper.ZookeeperConfiguration;
import io.elasticjob.lite.reg.zookeeper.ZookeeperRegistryCenter;
import job.SimpleJobDemo;

public class Main {
    public static void main(String[] args) {


        new JobScheduler(createRegistryCenter(), createJobConfiguration("A")).init();
    }
    private static CoordinatorRegistryCenter createRegistryCenter() {
        //192.168.112.128:2181,192.168.112.128:2182 这个为zk的地址
        //demo-job 这个为1个zk环境的下的1个namespace 可以有多个 1个namespace下有多个job
        ZookeeperConfiguration zkConf = new ZookeeperConfiguration("localhost:2181", "demo-job3");
        zkConf.setSessionTimeoutMilliseconds(100000);
        zkConf.setConnectionTimeoutMilliseconds(100000);
        zkConf.setMaxSleepTimeMilliseconds(30000);
        zkConf.setMaxRetries(3);
        CoordinatorRegistryCenter regCenter = new ZookeeperRegistryCenter(zkConf);
        regCenter.init();
        return regCenter;
    }
    private static LiteJobConfiguration createJobConfiguration(String jobParameter) {
        // mySimpleTest 为jobname 0/10 * * *     *                                                    ?为cron表达式  2 分片数量  0=北京,1=上海 分片对应内容  jobParameter 自定义参数
        JobCoreConfiguration simpleCoreConfig = JobCoreConfiguration.newBuilder("simpleJobDemo", "0/5 * * * * ?", 10)
                                                 .shardingItemParameters("0=北京,1=上海,2=深圳,3=广州,4=成都,5=西安,6=天津,7=济南,8=重庆,9=南京")
                                                    .jobParameter(jobParameter)
                                                    .build();
        //保存了job的配置，具体的job类
        SimpleJobConfiguration simpleJobConfig = new SimpleJobConfiguration(simpleCoreConfig, SimpleJobDemo.class.getCanonicalName());
        LiteJobConfiguration simpleJobRootConfig = LiteJobConfiguration.newBuilder(simpleJobConfig).build();
        return simpleJobRootConfig;
    }
}
