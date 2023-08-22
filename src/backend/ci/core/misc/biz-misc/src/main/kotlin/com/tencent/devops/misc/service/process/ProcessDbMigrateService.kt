/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.misc.service.process

import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.enums.SystemModuleEnum
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.pojo.ShardingRoutingRule
import com.tencent.devops.common.api.pojo.ShardingRuleTypeEnum
import com.tencent.devops.common.api.util.ShardingUtil
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.db.pojo.MIGRATING_DATA_SOURCE_NAME_PREFIX
import com.tencent.devops.common.db.pojo.MIGRATING_SHARDING_DSL_CONTEXT
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.CommonUtils
import com.tencent.devops.common.service.utils.SpringContextUtil
import com.tencent.devops.misc.dao.process.ProcessDao
import com.tencent.devops.misc.dao.process.ProcessDbMigrateDao
import com.tencent.devops.misc.pojo.process.MigratePipelineDataParam
import com.tencent.devops.misc.service.project.DataSourceService
import com.tencent.devops.misc.task.MigratePipelineDataTask
import com.tencent.devops.project.api.service.ServiceShardingRoutingRuleResource
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.annotation.Resource

@Suppress("TooManyFunctions", "LongMethod", "LargeClass")
@Service
class ProcessDbMigrateService @Autowired constructor(
    private val dslContext: DSLContext,
    @Resource(name = MIGRATING_SHARDING_DSL_CONTEXT) private val migratingShardingDslContext: DSLContext,
    private val processDao: ProcessDao,
    private val processDbMigrateDao: ProcessDbMigrateDao,
    private val dataSourceService: DataSourceService,
    private val redisOperation: RedisOperation
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ProcessDbMigrateService::class.java)
        private const val DEFAULT_THREAD_NUM = 10
        private const val DEFAULT_PAGE_SIZE = 20
        private const val DEFAULT_MIGRATION_TIMEOUT = 20L
        private const val SHORT_PAGE_SIZE = 5
        private const val MEDIUM_PAGE_SIZE = 100
        private const val LONG_PAGE_SIZE = 1000
    }

    @Value("\${sharding.migrationTimeout:#{20}}")
    private val migrationTimeout: Long = DEFAULT_MIGRATION_TIMEOUT

    fun migrateProjectData(
        userId: String,
        projectId: String,
        cancelFlag: Boolean = false,
        dataTag: String? = null
    ): Boolean {
        // 为项目分配路由规则
        val routingRuleMap = assignShardingRoutingRule(projectId, dataTag)
        // 开启异步任务迁移项目的数据
        Executors.newFixedThreadPool(1).submit {
            logger.info("migrateProjectData begin,params:[$userId|$projectId]")
            // 查询项目下流水线数量
            val pipelineNum = processDao.getPipelineNumByProjectId(dslContext, projectId)
            // 根据流水线数量计算线程数量
            val threadNum = if (pipelineNum < DEFAULT_THREAD_NUM) {
                pipelineNum
            } else {
                DEFAULT_THREAD_NUM
            }
            // 根据线程数量创建线程池
            val executor = Executors.newFixedThreadPool(threadNum)
            // 根据线程数量创建信号量
            val semaphore = Semaphore(threadNum)
            // 根据流水线数量创建计数器
            val doneSignal = CountDownLatch(pipelineNum)
            var minPipelineInfoId = processDao.getMinPipelineInfoIdByProjectId(dslContext, projectId)
            do {
                val pipelineIdList = processDao.getPipelineIdListByProjectId(
                    dslContext = dslContext,
                    projectId = projectId,
                    minId = minPipelineInfoId,
                    limit = DEFAULT_PAGE_SIZE.toLong()
                )?.map { it.getValue(0).toString() }
                if (!pipelineIdList.isNullOrEmpty()) {
                    // 重置minId的值
                    minPipelineInfoId = (processDao.getPipelineInfoByPipelineId(
                        dslContext = dslContext,
                        projectId = projectId,
                        pipelineId = pipelineIdList[pipelineIdList.size - 1]
                    )?.id ?: 0L) + 1
                }
                pipelineIdList?.forEach { pipelineId ->
                    executor.submit(
                        MigratePipelineDataTask(
                            migratePipelineDataParam = MigratePipelineDataParam(
                                projectId = projectId,
                                pipelineId = pipelineId,
                                cancelFlag = cancelFlag,
                                semaphore = semaphore,
                                doneSignal = doneSignal,
                                dslContext = dslContext,
                                migratingShardingDslContext = migratingShardingDslContext,
                                processDao = processDao,
                                processDbMigrateDao = processDbMigrateDao
                            )
                        )
                    )
                }
            } while (pipelineIdList?.size == DEFAULT_PAGE_SIZE)
            try {
                // 迁移与项目直接相关的数据
                doMigrationBus(projectId)
                // 等待所有任务执行完成
                doneSignal.await(migrationTimeout, TimeUnit.HOURS)
                // 执行迁移完成后的逻辑
                doAfterMigrationBus(userId, projectId, routingRuleMap)
            } catch (ignored: Throwable) {
                logger.warn("migrateProjectData fail|params:[$userId|$projectId]|error=${ignored.message}", ignored)
                // todo 删除迁移库的数据
            }
            logger.info("migrateProjectData end,params:[$userId|$projectId]")
        }
        return true
    }

    private fun doMigrationBus(projectId: String) {
        migrateAuditResourceData(projectId)
        migratePipelineGroupData(projectId)
        migratePipelineJobMutexGroupData(projectId)
        migratePipelineLabelData(projectId)
        migratePipelineTransferHistoryData(projectId)
        migratePipelineViewData(projectId)
        migratePipelineViewUserLastViewData(projectId)
        migratePipelineViewUserSettingsData(projectId)
        migrateProjectPipelineCallbackData(projectId)
        migrateProjectPipelineCallbackHistoryData(projectId)
        migrateTemplateData(projectId)
        migrateTemplatePipelineData(projectId)
        migrateTemplateTransferHistoryData(projectId)
        migratePipelineViewGroupData(projectId)
        migratePipelineViewTopData(projectId)
        migratePipelineRecentUseData(projectId)
    }

    private fun doAfterMigrationBus(userId: String, projectId: String, routingRuleMap: Map<String, String>) {
        val key = ShardingUtil.getMigratingShardingRoutingRuleKey(
            clusterName = CommonUtils.getDbClusterName(),
            moduleCode = SystemModuleEnum.PROCESS.name,
            ruleType = ShardingRuleTypeEnum.DB.name,
            routingName = projectId
        )
        val migratingShardingRoutingRule = redisOperation.get(key)
        val shardingRoutingRule = routingRuleMap[migratingShardingRoutingRule]
        if (migratingShardingRoutingRule != null && shardingRoutingRule != null) {
            // 清除缓存中项目的迁移DB路由规则
            redisOperation.delete(key)
            // 更新项目原来的路由规则
            val client = SpringContextUtil.getBean(Client::class.java)
            val updateResult = client.get(ServiceShardingRoutingRuleResource::class).updateShardingRoutingRule(
                userId = userId,
                shardingRoutingRule = ShardingRoutingRule(
                    clusterName = CommonUtils.getDbClusterName(),
                    moduleCode = SystemModuleEnum.PROCESS,
                    dataSourceName = shardingRoutingRule,
                    type = ShardingRuleTypeEnum.DB,
                    routingName = projectId,
                    routingRule = shardingRoutingRule
                )
            )
            if (updateResult.isNotOk()) {
                logger.warn("project[$projectId] updateShardingRoutingRule fail")
                throw ErrorCodeException(
                    errorCode = updateResult.status.toString(),
                    defaultMessage = updateResult.message
                )
            }
        }

        // todo 异步删除原库的数据
        // todo 发送迁移成功消息
    }

    private fun assignShardingRoutingRule(
        projectId: String,
        dataTag: String? = null
    ): Map<String, String> {
        val clusterName = CommonUtils.getDbClusterName()
        val moduleCode = SystemModuleEnum.PROCESS
        // 根据标签查找可用的数据源
        val dataSourceNames = dataSourceService.listByModule(
            clusterName = clusterName,
            moduleCode = moduleCode,
            fullFlag = false,
            dataTag = dataTag
        )?.map { it.dataSourceName }
        if (dataSourceNames.isNullOrEmpty()) {
            logger.warn("[$clusterName]$moduleCode has no dataSource available")
            throw ErrorCodeException(errorCode = CommonMessageCode.ERROR_CLIENT_REST_ERROR)
        }
        val maxSizeIndex = dataSourceNames.size - 1
        val randomIndex = (0..maxSizeIndex).random()
        val routingRule = "${MIGRATING_DATA_SOURCE_NAME_PREFIX}$randomIndex"
        val key = ShardingUtil.getMigratingShardingRoutingRuleKey(
            clusterName = CommonUtils.getDbClusterName(),
            moduleCode = SystemModuleEnum.PROCESS.name,
            ruleType = ShardingRuleTypeEnum.DB.name,
            routingName = projectId
        )
        // 把项目在迁移db集群的路由规则写入redis
        redisOperation.setIfAbsent(key, routingRule)
        return mapOf(routingRule to dataSourceNames[randomIndex])
    }

    private fun migrateAuditResourceData(projectId: String) {
        var offset = 0
        do {
            val auditResourceRecords = processDbMigrateDao.getAuditResourceRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if(auditResourceRecords.isNotEmpty()) {
                processDbMigrateDao.migrateAuditResourceData(migratingShardingDslContext, auditResourceRecords)
            }
            offset += LONG_PAGE_SIZE
        } while (auditResourceRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineGroupData(projectId: String) {
        var offset = 0
        do {
            val pipelineGroupRecords = processDbMigrateDao.getPipelineGroupRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if(pipelineGroupRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineGroupData(migratingShardingDslContext, pipelineGroupRecords)
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineGroupRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineJobMutexGroupData(projectId: String) {
        val jobMutexGroupRecords = processDbMigrateDao.getPipelineJobMutexGroupRecords(
            dslContext = dslContext,
            projectId = projectId
        )
        if(jobMutexGroupRecords.isNotEmpty()) {
            processDbMigrateDao.migratePipelineJobMutexGroupData(migratingShardingDslContext, jobMutexGroupRecords)
        }
    }

    private fun migratePipelineLabelData(projectId: String) {
        var offset = 0
        do {
            val pipelineLabelRecords = processDbMigrateDao.getPipelineLabelRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineLabelRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineLabelData(migratingShardingDslContext, pipelineLabelRecords)
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineLabelRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineTransferHistoryData(projectId: String) {
        var offset = 0
        do {
            val pipelineTransferHistoryRecords = processDbMigrateDao.getPipelineTransferHistoryRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineTransferHistoryRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineTransferHistoryData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineTransferHistoryRecords = pipelineTransferHistoryRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineTransferHistoryRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineViewData(projectId: String) {
        var offset = 0
        do {
            val pipelineViewRecords = processDbMigrateDao.getPipelineViewRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineViewRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineViewData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineViewRecords = pipelineViewRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineViewRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineViewUserLastViewData(projectId: String) {
        var offset = 0
        do {
            val pipelineViewUserLastViewRecords = processDbMigrateDao.getPipelineViewUserLastViewRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineViewUserLastViewRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineViewUserLastViewData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineViewUserLastViewRecords = pipelineViewUserLastViewRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineViewUserLastViewRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineViewUserSettingsData(projectId: String) {
        var offset = 0
        do {
            val pipelineViewUserSettingsRecords = processDbMigrateDao.getPipelineViewUserSettingsRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineViewUserSettingsRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineViewUserSettingsData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineViewUserSettingsRecords = pipelineViewUserSettingsRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineViewUserSettingsRecords.size == LONG_PAGE_SIZE)
    }

    private fun migrateProjectPipelineCallbackData(projectId: String) {
        var offset = 0
        do {
            val projectPipelineCallbackRecords = processDbMigrateDao.getProjectPipelineCallbackRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = MEDIUM_PAGE_SIZE,
                offset = offset
            )
            if (projectPipelineCallbackRecords.isNotEmpty()) {
                processDbMigrateDao.migrateProjectPipelineCallbackData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    projectPipelineCallbackRecords = projectPipelineCallbackRecords
                )
            }
            offset += MEDIUM_PAGE_SIZE
        } while (projectPipelineCallbackRecords.size == MEDIUM_PAGE_SIZE)
    }

    private fun migrateProjectPipelineCallbackHistoryData(projectId: String) {
        var offset = 0
        do {
            val pipelineCallbackHistoryRecords = processDbMigrateDao.getProjectPipelineCallbackHistoryRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = MEDIUM_PAGE_SIZE,
                offset = offset
            )
            if (pipelineCallbackHistoryRecords.isNotEmpty()) {
                processDbMigrateDao.migrateProjectPipelineCallbackHistoryData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineCallbackHistoryRecords = pipelineCallbackHistoryRecords
                )
            }
            offset += MEDIUM_PAGE_SIZE
        } while (pipelineCallbackHistoryRecords.size == MEDIUM_PAGE_SIZE)
    }

    private fun migrateTemplateData(projectId: String) {
        var offset = 0
        do {
            val templateRecords = processDbMigrateDao.getTemplateRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = SHORT_PAGE_SIZE,
                offset = offset
            )
            if (templateRecords.isNotEmpty()) {
                processDbMigrateDao.migrateTemplateData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    templateRecords = templateRecords
                )
            }
            offset += SHORT_PAGE_SIZE
        } while (templateRecords.size == SHORT_PAGE_SIZE)
    }

    private fun migrateTemplatePipelineData(projectId: String) {
        var offset = 0
        do {
            val templatePipelineRecords = processDbMigrateDao.getTemplatePipelineRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = SHORT_PAGE_SIZE,
                offset = offset
            )
            if (templatePipelineRecords.isNotEmpty()) {
                processDbMigrateDao.migrateTemplatePipelineData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    templatePipelineRecords = templatePipelineRecords
                )
            }
            offset += SHORT_PAGE_SIZE
        } while (templatePipelineRecords.size == SHORT_PAGE_SIZE)
    }

    private fun migrateTemplateTransferHistoryData(projectId: String) {
        var offset = 0
        do {
            val templateTransferHistoryRecords = processDbMigrateDao.getTemplateTransferHistoryRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (templateTransferHistoryRecords.isNotEmpty()) {
                processDbMigrateDao.migrateTemplateTransferHistoryData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    templateTransferHistoryRecords = templateTransferHistoryRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (templateTransferHistoryRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineViewGroupData(projectId: String) {
        var offset = 0
        do {
            val pipelineViewGroupRecords = processDbMigrateDao.getPipelineViewGroupRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineViewGroupRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineViewGroupData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineViewGroupRecords = pipelineViewGroupRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineViewGroupRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineViewTopData(projectId: String) {
        var offset = 0
        do {
            val pipelineViewTopRecords = processDbMigrateDao.getPipelineViewTopRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineViewTopRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineViewTopData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineViewTopRecords = pipelineViewTopRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineViewTopRecords.size == LONG_PAGE_SIZE)
    }

    private fun migratePipelineRecentUseData(projectId: String) {
        var offset = 0
        do {
            val pipelineRecentUseRecords = processDbMigrateDao.getPipelineRecentUseRecords(
                dslContext = dslContext,
                projectId = projectId,
                limit = LONG_PAGE_SIZE,
                offset = offset
            )
            if (pipelineRecentUseRecords.isNotEmpty()) {
                processDbMigrateDao.migratePipelineRecentUseData(
                    migratingShardingDslContext = migratingShardingDslContext,
                    pipelineRecentUseRecords = pipelineRecentUseRecords
                )
            }
            offset += LONG_PAGE_SIZE
        } while (pipelineRecentUseRecords.size == LONG_PAGE_SIZE)
    }
}
