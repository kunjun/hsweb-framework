package org.hswebframework.web.workflow.service.imp;

import lombok.SneakyThrows;
import org.activiti.engine.history.HistoricActivityInstance;
import org.activiti.engine.history.HistoricProcessInstance;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.impl.RepositoryServiceImpl;
import org.activiti.engine.impl.TaskServiceImpl;
import org.activiti.engine.impl.persistence.entity.ExecutionEntity;
import org.activiti.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.activiti.engine.impl.pvm.PvmActivity;
import org.activiti.engine.impl.pvm.PvmTransition;
import org.activiti.engine.impl.pvm.delegate.ActivityExecution;
import org.activiti.engine.impl.pvm.process.ActivityImpl;
import org.activiti.engine.impl.pvm.process.TransitionImpl;
import org.activiti.engine.runtime.Execution;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.hswebframework.ezorm.rdb.executor.SqlExecutor;
import org.hswebframework.utils.StringUtils;
import org.hswebframework.web.BusinessException;
import org.hswebframework.web.NotFoundException;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.User;
import org.hswebframework.web.workflow.dao.entity.ProcessHistoryEntity;
import org.hswebframework.web.workflow.service.ProcessHistoryService;
import org.hswebframework.web.workflow.service.config.ProcessConfigurationService;
import org.hswebframework.web.workflow.service.BpmActivityService;
import org.hswebframework.web.workflow.service.BpmTaskService;
import org.hswebframework.web.workflow.flowable.utils.JumpTaskCmd;
import org.hswebframework.web.workflow.service.WorkFlowFormService;
import org.hswebframework.web.workflow.service.config.CandidateInfo;
import org.hswebframework.web.workflow.service.request.CompleteTaskRequest;
import org.hswebframework.web.workflow.service.request.RejectTaskRequest;
import org.hswebframework.web.workflow.service.request.SaveFormRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


/**
 * @Author wangwei
 * @Date 2017/8/7.
 */
@Service
@Transactional(rollbackFor = Throwable.class)
public class BpmTaskServiceImpl extends AbstractFlowableService implements BpmTaskService {

    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private BpmActivityService bpmActivityService;

    @Autowired
    private ProcessConfigurationService processConfigurationService;

    @Autowired
    private WorkFlowFormService workFlowFormService;

    @Autowired
    private ProcessHistoryService processHistoryService;

    @Autowired
    private SqlExecutor sqlExecutor;

    @Override
    public List<Task> selectNowTask(String procInstId) {
        return taskService.createTaskQuery()
                .processInstanceId(procInstId)
                .active().list();
    }

    @Override
    public List<Task> selectTaskByProcessId(String procInstId) {
        return taskService.createTaskQuery().processInstanceId(procInstId).active().list();
    }

    @Override
    public Task selectTaskByTaskId(String taskId) {
        return taskService.createTaskQuery().taskId(taskId).active().singleResult();
    }

    @Override
    public void claim(String taskId, String userId) {
        Task task = taskService.createTaskQuery().
                taskId(taskId)
                .taskCandidateUser(userId)
                .active()
                .singleResult();
        if (task == null) {
            throw new NotFoundException("无法签收此任务");
        }
        if (!StringUtils.isNullOrEmpty(task.getAssignee())) {
            throw new BusinessException("任务已签售");
        } else {
            taskService.claim(taskId, userId);
        }
    }

    @Override
    public void complete(CompleteTaskRequest request) {
        request.tryValidate();

        Task task = taskService.createTaskQuery()
                .taskId(request.getTaskId())
                .includeProcessVariables()
                .active()
                .singleResult();

        Objects.requireNonNull(task, "任务不存在");
        String assignee = task.getAssignee();
        Objects.requireNonNull(assignee, "任务未签收");
        if (!assignee.equals(request.getCompleteUserId())) {
            throw new BusinessException("只能完成自己的任务");
        }
        Map<String, Object> variable = new HashMap<>();
        variable.put("oldTaskId", task.getId());
        Map<String, Object> transientVariables = new HashMap<>();

        if (null != request.getVariables()) {
            variable.putAll(request.getVariables());
            transientVariables.putAll(request.getVariables());
        }

        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();

        //保存表单数据
        workFlowFormService.saveTaskForm(instance, task, SaveFormRequest.builder()
                .userName(request.getCompleteUserName())
                .userId(request.getCompleteUserId())
                .formData(request.getFormData())
                .build());

        if (null != request.getFormData()) {
            transientVariables.putAll(request.getFormData());
        }

        taskService.complete(task.getId(), variable, transientVariables);

        //跳转
        if (!StringUtils.isNullOrEmpty(request.getNextActivityId())) {
            jumpTask(task, request.getNextActivityId());
        }

        //下一步候选人
        List<Task> tasks = selectNowTask(task.getProcessInstanceId());
        for (Task next : tasks) {
            if (!StringUtils.isNullOrEmpty(request.getNextClaimUserId())) {
                taskService.addCandidateUser(next.getId(), request.getNextClaimUserId());
            } else {
                setCandidate(request.getCompleteUserId(), next);
            }
        }

        ProcessHistoryEntity history = ProcessHistoryEntity.builder()
                .businessKey(instance.getBusinessKey())
                .type("complete")
                .typeText("完成任务")
                .creatorId(request.getCompleteUserId())
                .creatorName(request.getCompleteUserName())
                .processDefineId(instance.getProcessDefinitionId())
                .processInstanceId(instance.getProcessInstanceId())
                .taskId(task.getId())
                .taskDefineKey(task.getTaskDefinitionKey())
                .taskName(task.getName())
                .build();

        processHistoryService.insert(history);
    }


    @Override
    @SneakyThrows
    public void reject(RejectTaskRequest request) {
        request.tryValidate();
        String activityId = request.getActivityId();

        //从任务历史中查找
        HistoricTaskInstance task = historyService.createHistoricTaskInstanceQuery()
                .taskDefinitionKey(activityId)
                .processInstanceId(request.getProcessInstanceId())
                .singleResult();
        if (task == null) {
            throw new NotFoundException("历史任务环节不存在");
        }

        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .singleResult();
        if (processInstance == null) {
            throw new NotFoundException("流程已经结束");
        }

        Map<String, Object> variables = processInstance.getProcessVariables();

        ProcessDefinitionEntity definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService).getDeployedProcessDefinition(task.getProcessDefinitionId());
        if (definition == null) {
            throw new BusinessException("流程定义未找到");
        }

        ActivityExecution execution = (ActivityExecution) runtimeService.createExecutionQuery()
                .executionId(task.getExecutionId()).singleResult();
        // 是否并行节点
        if (execution.isConcurrent()) {
            throw new BusinessException("并行节点不允许驳回");
        }

        // 是否存在定时任务
        long num = managementService.createJobQuery().executionId(task.getExecutionId()).count();
        if (num > 0) {
            throw new BusinessException("当前环节不允许驳回");
        }

        // 取得上一步活动
        ActivityImpl currActivity = definition.findActivity(task.getTaskDefinitionKey());
        List<PvmTransition> nextTransitionList = currActivity.getIncomingTransitions();
        // 清除当前活动的出口
        List<PvmTransition> oriPvmTransitionList = new ArrayList<>();
        List<PvmTransition> pvmTransitionList = currActivity.getOutgoingTransitions();
        oriPvmTransitionList.addAll(pvmTransitionList);
        pvmTransitionList.clear();

        // 建立新出口
        List<TransitionImpl> newTransitions = new ArrayList<>();
        for (PvmTransition nextTransition : nextTransitionList) {
            PvmActivity nextActivity = nextTransition.getSource();
            ActivityImpl nextActivityImpl = definition.findActivity(nextActivity.getId());
            TransitionImpl newTransition = currActivity.createOutgoingTransition();
            newTransition.setDestination(nextActivityImpl);
            newTransitions.add(newTransition);
        }
        // 完成任务
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstance.getId())
                .taskDefinitionKey(task.getTaskDefinitionKey())
                .list();

        for (Task t : tasks) {
            taskService.complete(t.getId(), variables);
            historyService.deleteHistoricTaskInstance(t.getId());
            //删除连线
            List<HistoricActivityInstance> instance = historyService.createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstance.getProcessInstanceId())
                    .activityId(t.getTaskDefinitionKey())
                    .list();
            for (HistoricActivityInstance historicActivityInstance : instance) {
                sqlExecutor.delete("delete from act_hi_actinst where id_= #{id}", historicActivityInstance);
            }
        }
       // 恢复方向
        for (TransitionImpl transitionImpl : newTransitions) {
            currActivity.getOutgoingTransitions().remove(transitionImpl);
        }
        pvmTransitionList.addAll(oriPvmTransitionList);

        //重新设置候选人
        List<Task> nowTasks = selectNowTask(processInstance.getProcessInstanceId());
        Task tmp = null;
        for (Task nowTask : nowTasks) {
            setCandidate(request.getRejectUserId(), nowTask);
            tmp = nowTask;
        }

        ProcessHistoryEntity history = ProcessHistoryEntity.builder()
                .businessKey(processInstance.getBusinessKey())
                .type("reject")
                .typeText("驳回")
                .creatorId(request.getRejectUserId())
                .creatorName(request.getRejectUserName())
                .processDefineId(processInstance.getProcessDefinitionId())
                .processInstanceId(processInstance.getProcessInstanceId())
                .taskId(tmp != null ? tmp.getId() : null)
                .taskDefineKey(tmp != null ? tmp.getTaskDefinitionKey() : null)
                .taskName(tmp != null ? tmp.getName() : null)
                .data(request.getData())
                .build();

        historyService.deleteHistoricTaskInstance(task.getId());
        processHistoryService.insert(history);
    }

    public Task jumpTask(Task task, String activityId) {
        TaskServiceImpl taskServiceImpl = (TaskServiceImpl) taskService;
        taskServiceImpl.getCommandExecutor().execute(new JumpTaskCmd(task.getExecutionId(), activityId));
        return task;
    }

    @Override
    public Task jumpTask(String taskId, String activity) {
        return jumpTask(selectTaskByTaskId(taskId), activity);
    }

    @Override
    public void setAssignee(String taskId, String userId) {
        taskService.setAssignee(taskId, userId);
    }

    @Override
    public void endProcess(String procInstId) {
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(procInstId).singleResult();
        ActivityImpl activity = bpmActivityService.getEndEvent(processInstance.getProcessDefinitionId());
        jumpTask(procInstId, activity.getId());
    }

    @Override
    public void removeHiTask(String taskId) {
        historyService.deleteHistoricTaskInstance(taskId);
    }

    @Override
    public Map<String, Object> selectVariableLocalByTaskId(String taskId) {
        return taskService.getVariablesLocal(taskId);
    }

    @Override
    public String selectVariableLocalByTaskId(String taskId, String variableName) {
        return (String) taskService.getVariableLocal(taskId, variableName);
    }

    @Override
    public HistoricProcessInstance selectHisProInst(String procInstId) {
        return historyService.createHistoricProcessInstanceQuery().processInstanceId(procInstId).singleResult();
    }

    @Override
    public void setCandidate(String doingUserId, Task task) {
        if (task == null) {
            return;
        }
        if (task.getTaskDefinitionKey() != null) {
            //从配置中获取候选人
            List<CandidateInfo> candidateInfoList = processConfigurationService
                    .getActivityConfiguration(doingUserId, task.getProcessDefinitionId(), task.getTaskDefinitionKey())
                    .getCandidateInfo(task);

            for (CandidateInfo candidateInfo : candidateInfoList) {
                Authentication user = candidateInfo.user();
                if (user != null) {
                    taskService.addCandidateUser(task.getId(), user.getUser().getId());
                }
            }
        } else {
            logger.warn("未能成功设置环节候选人,task:{}", task);
        }
    }

    @Override
    public ActivityImpl selectActivityImplByTask(String taskId) {
        if (StringUtils.isNullOrEmpty(taskId)) {
            return new ActivityImpl(null, null);
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        ProcessDefinitionEntity entity = (ProcessDefinitionEntity) ((RepositoryServiceImpl) repositoryService).getDeployedProcessDefinition(task.getProcessDefinitionId());
        List<ActivityImpl> activities = entity.getActivities();
        return activities
                .stream()
                .filter(activity -> "userTask".equals(activity.getProperty("type")) && activity.getProperty("name").equals(task.getName()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("获取节点信息失败"));
    }

    @Override
    public Map<String, Object> getUserTasksByProcDefKey(String procDefKey) {
        String definitionId = repositoryService.createProcessDefinitionQuery().processDefinitionKey(procDefKey).orderByProcessDefinitionVersion().desc().list().get(0).getId();
        List<ActivityImpl> activities = bpmActivityService.getUserTasksByProcDefId(definitionId);
        Map<String, Object> map = new HashMap<>();
        for (ActivityImpl activity : activities) {
            map.put(activity.getId(), activity.getProperty("name"));
        }
        return map;
    }

    @Override
    public Map<String, Object> getUserTasksByProcInstId(String procInstId) {
        String definitionId = runtimeService.createProcessInstanceQuery().processInstanceId(procInstId).singleResult().getProcessDefinitionId();
        List<ActivityImpl> activities = bpmActivityService.getUserTasksByProcDefId(definitionId);
        Map<String, Object> map = new HashMap<>();
        for (ActivityImpl activity : activities) {
            map.put(activity.getId(), activity.getProperty("name"));
        }
        return map;
    }

    @Override
    public void setVariables(String taskId, Map<String, Object> map) {
        taskService.setVariables(taskId, map);
    }

    @Override
    public void removeVariables(String taskId, Collection<String> var2) {
        taskService.removeVariables(taskId, var2);
    }

    @Override
    public void setVariablesLocal(String taskId, Map<String, Object> map) {
        taskService.setVariablesLocal(taskId, map);
    }

    @Override
    public Map<String, Object> getVariablesByProcInstId(String procInstId) {
        List<Execution> executions = runtimeService.createExecutionQuery().processInstanceId(procInstId).list();
        String executionId = "";
        for (Execution execution : executions) {
            if (StringUtils.isNullOrEmpty(execution.getParentId())) {
                executionId = execution.getId();
            }
        }
        return runtimeService.getVariables(executionId);
    }

    @Override
    public Map<String, Object> getVariablesByTaskId(String taskId) {
        return taskService.getVariables(taskId);
    }
}