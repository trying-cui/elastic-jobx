package com.dangdang.ddframe.job.console.listener;

import com.dangdang.ddframe.job.console.domain.RegistryCenterConfiguration;
import com.dangdang.ddframe.job.console.service.JobTriggerHistoryService;
import com.dangdang.ddframe.job.console.service.RegistryCenterService;
import com.dangdang.ddframe.job.console.zookeeper.ConsoleRegistryCenter;
import com.dangdang.ddframe.job.internal.console.ConsoleNode;
import com.dangdang.ddframe.job.internal.listener.AbstractJobListener;
import com.dangdang.ddframe.job.internal.reg.RegistryCenterFactory;
import com.google.common.base.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.TreeCacheEvent;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collection;

/**
 * 作业命名空间观察者管理器
 *
 * Created by xiong.j on 2016/9/2.
 */

@Slf4j
@Component
public class ObserverManager{

    @Autowired
    private RegistryCenterService registryCenterService;

    @Autowired
    private JobTriggerHistoryService jobTriggerHistoryService;

    @Autowired
    private ConsoleRegistryCenter registryCenter;

    private RegistryCenterObserver registryCenterObserver;

    @PostConstruct
    public void init() {
        try {
            // 连接ZK,选举Leader启动监控
            startMonitor().startRegistryCenter();
            log.info("Succeed to init ObserverManager.");
        } catch (Exception e) {
            log.error("Failed to init ObserverManager.", e);
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroy ObserverManager.");
        RegistryCenterFactory.getInstance().deleteObservers();
        RegistryCenterFactory.getInstance().destroy();
        registryCenter.close();
    }

    private void startRegistryCenter() throws Exception {
        registryCenter.init()
                .addCacheData(ConsoleNode.NAMESPACE)
                .addDataListener(new NeedMonitorNamespaceListener(), ConsoleNode.NAMESPACE)
                .startLeaderElect(new LeaderChangedLatchListener());
    }

    private ObserverManager startMonitor(){
        // 加上监控
        registryCenterObserver = new RegistryCenterObserver(jobTriggerHistoryService, registryCenter);
        RegistryCenterFactory.getInstance().addObserver(registryCenterObserver);

        // 连接既有的命名空间
        Collection<RegistryCenterConfiguration> registryCenterConfigurations = registryCenterService.loadAll();
        for (RegistryCenterConfiguration configuration : registryCenterConfigurations) {
            createCoordinatorRegistryCenter(configuration);
        }
        return this;
    }

    private void createCoordinatorRegistryCenter(RegistryCenterConfiguration configuration) {
        RegistryCenterFactory.createCoordinatorRegistryCenter(configuration.getZkAddressList(),
                configuration.getNamespace(), Optional.fromNullable(configuration.getDigest()));
    }

    class LeaderChangedLatchListener implements LeaderLatchListener{

        @Override
        public void isLeader() {
            log.info("###### Get leadership.  ######");
            registryCenterObserver.start();
        }

        @Override
        public void notLeader() {
            log.info("###### Lost leadership. ######");
            registryCenterObserver.close();
        }
    }

    class NeedMonitorNamespaceListener extends AbstractJobListener {

        @Override
        protected void dataChanged(final CuratorFramework client, final TreeCacheEvent event, final String path) {
            if (registryCenter.hasLeadership()) {
                if (TreeCacheEvent.Type.NODE_ADDED == event.getType() && path.startsWith(ConsoleNode.NAMESPACE)) {
                    String namespace = path.substring(path.lastIndexOf("/") + 1);
                    RegistryCenterConfiguration configuration = registryCenterService.load(namespace);
                    createCoordinatorRegistryCenter(configuration);
                    registryCenter.removeNode(path);
                }
            }

        }
    }
}
