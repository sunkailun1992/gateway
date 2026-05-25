package com.gb.filter;

import com.alibaba.cloud.nacos.ribbon.NacosServer;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.RoundRobinRule;
import com.netflix.loadbalancer.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @ClassName RewriteRoundRobinRule
 * @Description 重写负载均衡器
 * @Author 孙凯伦
 * @Mobile 13777579028
 * @Email 376253703@qq.com
 * @Time 2023/12/15 10:41 上午
 */
@Component
@Slf4j
public class RewriteRoundRobinRule extends RoundRobinRule {

    private AtomicInteger nextServerCyclicCounter;
    private static final boolean AVAILABLE_ONLY_SERVERS = true;
    private static final boolean ALL_SERVERS = false;

    public RewriteRoundRobinRule() {
        nextServerCyclicCounter = new AtomicInteger(0);
    }

    public Server choose(ILoadBalancer lb, Object key) {
        //负载均衡器判断
        if (lb == null) {
            log.warn("没有负载平衡器");
            return null;
        }
        /**
         * 负载均衡器服务获取重试
         */
        Server server = null;
        int count = 0;
        while (server == null && count++ < 10) {
            //访问负载服务列表
            List<Server> reachableServers = lb.getReachableServers();
            //所有负载服务列表
            List<Server> allServers = lb.getAllServers();
            //访问负载服务总数
            int upCount = reachableServers.size();
            //所有负载服务总数
            int serverCount = allServers.size();
            //没有负载服务抛出警告
            if ((upCount == 0) || (serverCount == 0)) {
                log.warn("负载均衡器中没有可用的服务器：" + lb);
                return null;
            }
            //传输版本号对应负载服务版本
            List<NacosServer> filterServers = new ArrayList<>();
            //传输的版本号
            String currentEnvironmentVersion = "3.2.0";
            /**
             * 所有服务循环判断
             */
            for (Server serverInfo : allServers) {
                //负载服务信息
                NacosServer nacosServer = (NacosServer) serverInfo;
                //获得负载服务版本号
                String version = nacosServer.getMetadata().get("version");
                //传输版本号和负载服务版本号判断
                if (version.equals(currentEnvironmentVersion)) {
                    filterServers.add(nacosServer);
                }
            }
            //服务总数
            int filterServerCount = filterServers.size();
            //均衡求模
            int nextServerIndex = incrementAndGetModulo(filterServerCount);
            //取出对应负载服务
            server = filterServers.get(nextServerIndex);
            //负载服务为空，关闭
            if (server == null) {
                /* Transient. */
                Thread.yield();
                continue;
            }
            //返回服务
            if (server.isAlive() && (server.isReadyToServe())) {
                return (server);
            }

            //设置为空，下一个请求
            server = null;
        }

        if (count >= 10) {
            log.warn("从负载平衡器尝试10次后，没有可用的活动服务器: "
                    + lb);
        }
        return server;
    }

    /**
     * TODO 均衡求模
     *
     * @param modulo
     * @return int
     * @author 孙凯伦
     * @methodName incrementAndGetModulo
     * @time 2023/12/15 12:07
     */
    private int incrementAndGetModulo(int modulo) {
        for (; ; ) {
            int current = nextServerCyclicCounter.get();
            int next = (current + 1) % modulo;
            if (nextServerCyclicCounter.compareAndSet(current, next)) {
                return next;
            }
        }
    }
}
