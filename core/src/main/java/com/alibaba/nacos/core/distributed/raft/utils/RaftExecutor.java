/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.distributed.raft.utils;

import com.alibaba.nacos.core.distributed.raft.JRaftServer;
import com.alibaba.nacos.core.distributed.raft.RaftConfig;
import com.alibaba.nacos.core.distributed.raft.RaftSysConstants;
import com.alibaba.nacos.common.executor.ExecutorFactory;
import com.alibaba.nacos.common.executor.NameThreadFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public final class RaftExecutor {

	private static ExecutorService raftCoreExecutor;
	private static ExecutorService raftCliServiceExecutor;
	private static ScheduledExecutorService raftMemberRefreshExecutor;

	private static final String OWNER = JRaftServer.class.getName();

	private RaftExecutor() {
	}

	public static void init(RaftConfig config) {

		int raftCoreThreadNum = Integer.parseInt(config.getValOfDefault(RaftSysConstants.RAFT_CORE_THREAD_NUM, "8"));
		int raftCliServiceThreadNum = Integer.parseInt(config.getValOfDefault(RaftSysConstants.RAFT_CLI_SERVICE_THREAD_NUM, "4"));

		raftCoreExecutor = ExecutorFactory.newFixExecutorService(OWNER, raftCoreThreadNum,
				new NameThreadFactory("com.alibaba.naocs.core.raft-core"));

		raftCliServiceExecutor = ExecutorFactory
				.newFixExecutorService(OWNER, raftCliServiceThreadNum,
						new NameThreadFactory("com.alibaba.naocs.core.raft-cli-service"));

		raftMemberRefreshExecutor = ExecutorFactory.newScheduledExecutorService(OWNER, 8,
				new NameThreadFactory(
						"com.alibaba.nacos.core.protocol.raft-member-refresh"));

	}

	public static void scheduleRaftMemberRefreshJob(Runnable runnable, long initialDelay,
			long period, TimeUnit unit) {
		raftMemberRefreshExecutor
				.scheduleAtFixedRate(runnable, initialDelay, period, unit);
	}

	public static ExecutorService getRaftCoreExecutor() {
		return raftCoreExecutor;
	}

	public static ExecutorService getRaftCliServiceExecutor() {
		return raftCliServiceExecutor;
	}

	public static ScheduledExecutorService getRaftMemberRefreshExecutor() {
		return raftMemberRefreshExecutor;
	}

	public static void executeByRaftCore(Runnable runnable) {
		raftCoreExecutor.execute(runnable);
	}

	public static void doSnapshot(Runnable runnable) {
		raftCoreExecutor.execute(runnable);
	}

}
