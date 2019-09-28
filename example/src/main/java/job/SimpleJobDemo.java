package job;



import io.elasticjob.lite.api.ShardingContext;
import io.elasticjob.lite.api.dataflow.DataflowJob;
import io.elasticjob.lite.api.script.ScriptJob;
import io.elasticjob.lite.api.simple.SimpleJob;

import java.util.Date;
import java.util.List;

public class SimpleJobDemo implements SimpleJob {
    public void execute(ShardingContext shardingContext) {

        System.out.println(new Date()+" job名称 = "+shardingContext.getJobName()
                +"分片数量"+shardingContext.getShardingTotalCount()
                +"当前分区"+shardingContext.getShardingItem()
                +"当前分区名称"+shardingContext.getShardingParameter()
                +"当前自定义参数"+shardingContext.getJobParameter()+"============start=================");


    }
}


class DataFlowJobDemo implements DataflowJob {


    @Override
    public List fetchData(ShardingContext shardingContext) {
        return null;
    }


    public void processData(ShardingContext shardingContext, List data) {

    }
}


class SciptJobdemo implements ScriptJob {

}

